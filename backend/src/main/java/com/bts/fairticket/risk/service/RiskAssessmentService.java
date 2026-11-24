package com.bts.fairticket.risk.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bts.fairticket.risk.client.AiInferenceClient;
import com.bts.fairticket.risk.client.AiInferenceClient.AiFeatures;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPredictRequest;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPrediction;
import com.bts.fairticket.risk.dto.UserCalculatedFeatures;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.entity.RiskAssessmentCursor;
import com.bts.fairticket.risk.event.RiskAssessedEvent;
import com.bts.fairticket.risk.repository.RiskAssessmentCursorRepository;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.user.repository.UserAccountRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 새 행동 이벤트가 들어온 사용자의 Feature를 집계해 ticket-fds-ai에 배치로 보내고,
 * 결과를 risk_assessment에 저장한다 (#40 2단계).
 * <ul>
 *   <li>OK: 평가를 저장하고 RiskAssessedEvent를 발행한다(커밋 후 Credit 재산출).</li>
 *   <li>INSUFFICIENT_DATA: 저장하지 않는다. 정상(0점)으로 취급하지 않는다.</li>
 *   <li>INVALID: 저장하지 않고 경고만 남긴다(Feature 계산 규칙 불일치 신호).</li>
 * </ul>
 * 세 경우 모두 진행 위치(cursor)를 기록해 같은 이벤트로 반복 호출하지 않는다.
 * 결과를 저장하기 직전에 사용자 행을 잠그고 진행 위치가 후보 선정 때와 같은지 확인한다. 그 사이 다른 실행이
 * 이미 평가를 반영했다면 이 결과는 버린다(평가 저장 · 진행 위치 갱신 · Credit 갱신 이벤트 모두 하지 않음, #63).
 * AI 호출 중에는 DB 잠금을 잡지 않는다.
 * AI 서버 장애 시에는 진행 위치를 바꾸지 않으므로 다음 주기에 그대로 다시 시도한다.
 */
@Slf4j
@Service
public class RiskAssessmentService {

	static final String OK = "OK";
	static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
	/** 후보 선정 이후 진행 위치가 바뀌어 버린 결과. DB에는 기록하지 않는다. */
	static final String DISCARDED = "DISCARDED";
	private static final DateTimeFormatter CURSOR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final RiskAssessmentCursorRepository cursors;
	private final FeatureAggregationService features;
	private final AiInferenceClient ai;
	private final UserAccountRepository users;
	private final RiskAssessmentRepository assessments;
	private final ApplicationEventPublisher events;
	private final TransactionTemplate perUser;
	private final Clock clock;
	private final int batchSize;
	private final int maxUsersPerRun;

	public RiskAssessmentService(RiskAssessmentCursorRepository cursors, FeatureAggregationService features,
			AiInferenceClient ai, UserAccountRepository users, RiskAssessmentRepository assessments,
			ApplicationEventPublisher events, PlatformTransactionManager transactions, Clock clock,
			@Value("${bts.ai.batch-size:100}") int batchSize,
			@Value("${bts.ai.max-users-per-run:500}") int maxUsersPerRun) {
		this.cursors = cursors;
		this.features = features;
		this.ai = ai;
		this.users = users;
		this.assessments = assessments;
		this.events = events;
		this.perUser = new TransactionTemplate(transactions);
		this.perUser.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("bts.ai.batch-size는 1~1000이어야 합니다.");
		this.batchSize = batchSize;
		this.maxUsersPerRun = Math.max(1, maxUsersPerRun);
	}

	/** discarded: 후보 선정 이후 다른 실행이 먼저 반영해 버린 결과 수. */
	public record RunSummary(int candidates, int assessed, int insufficient, int invalid, int discarded) { }

	private record Prepared(String userId, long advanceToEventId, Long seenLastEventId, String seenCheckedAt,
			UserCalculatedFeatures features, Integer recentIpAttemptCount) { }

	/** 한 주기 실행. AI 서버 장애면 AiUnavailableException을 던지고, 이미 처리한 배치의 결과는 유지된다. */
	public RunSummary runOnce() {
		// 한 주기 안에서는 같은 분석 시각을 쓴다. 후보 선정, Feature 집계, 진행 위치의 checked_at이 모두 같은 값이어야
		// "checked_at 이후에 발생한 이벤트 = 지난 평가에 반영되지 않은 이벤트"가 정확히 성립한다(누락도 중복도 없음).
		// checked_at이 초 단위 DATETIME이므로 분석 시각도 초 단위로 버린다. 현재 초 안에 발생한 이벤트(밀리초 비교로
		// 분석 시각보다 뒤)는 이번 집계와 후보에서 함께 빠지고 다음 주기에 평가된다.
		LocalDateTime calculatedAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.SECONDS);
		var candidates = cursors.findCandidates(calculatedAt, maxUsersPerRun);
		if (candidates.isEmpty()) return new RunSummary(0, 0, 0, 0, 0);

		// AI는 UTC 오프셋이 붙은 ISO-8601을 요구한다 (예: 2026-11-24T05:00:00Z).
		String calculatedAtIso = calculatedAt.toInstant(ZoneOffset.UTC).toString();

		int assessed = 0, insufficient = 0, invalid = 0, discarded = 0;
		for (int from = 0; from < candidates.size(); from += batchSize) {
			var chunk = candidates.subList(from, Math.min(from + batchSize, candidates.size()));
			List<Prepared> prepared = new ArrayList<>(chunk.size());
			List<AiPredictRequest> requests = new ArrayList<>(chunk.size());
			for (var candidate : chunk) {
				try {
					UserCalculatedFeatures f = features.aggregateFeatures(candidate.getUserId(), calculatedAt);
					AiPredictRequest request = new AiPredictRequest(candidate.getUserId(), calculatedAtIso,
							FeatureAggregationService.WINDOW_DAYS,
							new AiFeatures(f.getBookingCount(), f.getAvgBookingInterval(), f.getBookingIntervalVariance(),
									f.getCancelRate(), f.getCardReuseCount(), f.getIpBookingDensity()));
					// Credit 룰 감점 입력값. AI 요청에는 넣지 않는다.
					Integer recentIpAttemptCount = features.recentIpAttemptCount(candidate.getUserId(), calculatedAt);
					prepared.add(new Prepared(candidate.getUserId(), candidate.getAdvanceToEventId(),
							candidate.getSeenLastEventId(), candidate.getSeenCheckedAt(), f, recentIpAttemptCount));
					requests.add(request);
				} catch (RuntimeException exception) {
					log.warn("AI feature aggregation failed (user={}): {}", candidate.getUserId(), exception.toString());
				}
			}
			if (requests.isEmpty()) continue;

			List<AiPrediction> results = ai.predictBatch(requests);

			for (int i = 0; i < prepared.size(); i++) {
				Prepared p = prepared.get(i);
				try {
					String status = apply(p, results.get(i), calculatedAt);
					switch (status) {
						case OK -> assessed++;
						case INSUFFICIENT_DATA -> insufficient++;
						case DISCARDED -> discarded++;
						default -> invalid++;
					}
				} catch (RuntimeException exception) {
					log.warn("AI assessment persistence failed (user={}): {}", p.userId(), exception.toString());
				}
			}
		}
		var summary = new RunSummary(candidates.size(), assessed, insufficient, invalid, discarded);
		log.info("AI risk assessment run: {}", summary);
		return summary;
	}

	private String apply(Prepared p, AiPrediction r, LocalDateTime checkedAt) {
		String status = r == null || r.status() == null ? "INVALID" : r.status();
		if (r != null && r.userId() != null && !p.userId().equals(r.userId())) {
			status = "INVALID"; // 응답 순서가 요청과 다르면 저장하지 않는다.
		}
		if (OK.equals(status) && (r.anomalyScore() == null || r.modelVersion() == null || r.modelVersion().isBlank()
				|| r.modelVersion().length() > 30 || r.anomalyScore() < 0 || r.anomalyScore() > 1)) {
			status = "INVALID";
		}
		final String finalStatus = status;
		Boolean applied = perUser.execute(tx -> {
			// 같은 사용자의 결과 반영을 직렬화한다. 이벤트 등록 · Credit 평가와 같은 user_account 행 잠금이다.
			var user = users.findByIdForUpdate(p.userId()).orElseThrow();
			RiskAssessmentCursor cursor = cursors.findById(p.userId()).orElse(null);
			if (!sameAsSeen(cursor, p)
					|| (cursor != null && checkedAt.isBefore(cursor.getCheckedAt()))) {
				log.info("AI assessment discarded: cursor changed or newer cutoff already applied (user={})", p.userId());
				return false;
			}
			if (OK.equals(finalStatus)) {
				RiskAssessment saved = assessments.save(new RiskAssessment(user, r.anomalyScore(), r.modelVersion()));
				events.publishEvent(new RiskAssessedEvent(p.userId(), saved.getAssessmentId(), r.anomalyScore(),
						p.features().getBookingIntervalVariance(), p.recentIpAttemptCount()));
			} else if (!INSUFFICIENT_DATA.equals(finalStatus)) {
				log.warn("AI rejected features (user={}, status={}, errors={})", p.userId(), finalStatus,
						r == null ? null : r.errors());
			}
			if (cursor == null) cursor = new RiskAssessmentCursor(p.userId());
			cursor.advance(p.advanceToEventId(), finalStatus, checkedAt);
			cursors.save(cursor);
			return true;
		});
		return Boolean.TRUE.equals(applied) ? finalStatus : DISCARDED;
	}

	/** 진행 위치가 후보 선정 때 읽은 값과 같은지. 커서가 없던 사용자는 지금도 없어야 한다. */
	private static boolean sameAsSeen(RiskAssessmentCursor cursor, Prepared p) {
		if (cursor == null) return p.seenLastEventId() == null;
		return Objects.equals(cursor.getLastEventId(), p.seenLastEventId())
				&& Objects.equals(CURSOR_TIME.format(cursor.getCheckedAt()), p.seenCheckedAt());
	}
}
