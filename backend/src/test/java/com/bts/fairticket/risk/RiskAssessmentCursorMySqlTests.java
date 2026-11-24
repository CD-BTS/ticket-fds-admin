package com.bts.fairticket.risk;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bts.fairticket.behavior.dto.EventRequest;
import com.bts.fairticket.behavior.dto.EventType;
import com.bts.fairticket.behavior.repository.BehaviorEventRepository;
import com.bts.fairticket.behavior.service.EventService;
import com.bts.fairticket.credit.dto.CreditEvaluationContext;
import com.bts.fairticket.credit.service.CreditEvaluationService;
import com.bts.fairticket.risk.client.AiInferenceClient;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPredictRequest;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPrediction;
import com.bts.fairticket.risk.repository.RiskAssessmentCursorRepository;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.risk.service.FeatureAggregationService;
import com.bts.fairticket.risk.service.RiskAssessmentService;
import com.bts.fairticket.user.ApiKeyTestSupport;
import com.bts.fairticket.user.dto.UserRegistrationRequest;
import com.bts.fairticket.user.repository.UserAccountRepository;
import com.bts.fairticket.user.service.UserService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #40: AI 평가 커서가 Feature 집계에 반영되지 않은 이벤트를 건너뛰지 않는지 실제 MySQL로 검증한다.
 * 후보 쿼리(findCandidates)와 Feature 집계는 실제 구현을 쓰고, AI 서버만 가짜로 둔다.
 * 실행할 때마다 만든 임의 서비스의 데이터만 만들고 지우며, 다른 사용자는 평가하지 않는다.
 */
@Tag("mysql")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RiskAssessmentCursorMySqlTests {
	private static final String SERVICE = "rc-" + UUID.randomUUID();
	private static final String OTHER_SERVICE = "rc-" + UUID.randomUUID();
	private static final String KEY = ApiKeyTestSupport.randomKey();
	private static final String NOW = "2026-11-24T05:00:00.789Z";

	@DynamicPropertySource
	static void keys(DynamicPropertyRegistry registry) {
		registry.add("BTS_API_KEY_HASHES", () -> ApiKeyTestSupport.configuration(Map.of(SERVICE, KEY)));
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private UserService userService;
	@Autowired private EventService eventService;
	@Autowired private CreditEvaluationService creditEvaluationService;
	@Autowired private RiskAssessmentCursorRepository cursorRepository;
	@Autowired private FeatureAggregationService featureAggregationService;
	@Autowired private UserAccountRepository userAccountRepository;
	@Autowired private RiskAssessmentRepository riskAssessmentRepository;
	@Autowired private ApplicationEventPublisher eventPublisher;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoSpyBean private BehaviorEventRepository behaviorEvents;

	private String userId;
	/** AI로 보낸 이 사용자의 요청. bookingCount로 어떤 이벤트가 집계에 반영됐는지 본다. */
	private final List<AiPredictRequest> sent = new ArrayList<>();

	@BeforeEach
	void createOwnUser() {
		userId = userService.register(new UserRegistrationRequest(SERVICE, UUID.randomUUID().toString()), SERVICE)
				.user().userId();
	}

	@AfterEach
	void cleanOnlyOwnRandomServiceFixtures() {
		String ownUsers = "(SELECT user_id FROM user_account WHERE service_id IN (?, ?))";
		for (String table : List.of("risk_assessment_cursor", "credit_history", "risk_assessment", "booking_history",
				"user_card", "behavior_event", "user_credit")) {
			jdbc.update("DELETE FROM " + table + " WHERE user_id IN " + ownUsers, SERVICE, OTHER_SERVICE);
		}
		jdbc.update("DELETE FROM user_account WHERE service_id IN (?, ?)", SERVICE, OTHER_SERVICE);
	}

	@Test
	void eventInTheCurrentSecondIsDeferredToTheNextRunAndAssessedExactlyOnce() {
		long earlier = attempt("2026-11-24 04:59:59.900");
		long sameSecond = attempt("2026-11-24 05:00:00.500"); // 실행 시각(05:00:00.789)과 같은 초

		// 분석 시각은 초 단위(05:00:00)다. 같은 초의 이벤트는 집계와 후보에서 함께 빠지고, 커서도 넘어가지 않는다.
		runAt(NOW);
		assertEquals(1, sent.size());
		assertEquals("2026-11-24T05:00:00Z", sent.get(0).calculatedAt());
		assertEquals(1, sent.get(0).features().bookingCount());
		assertEquals(earlier, cursor());
		assertEquals(1, assessmentCount());

		// 다음 실행에서 반영된다.
		runAt("2026-11-24T05:01:00.100Z");
		assertEquals(2, sent.size());
		assertEquals(2, sent.get(1).features().bookingCount());
		assertEquals(sameSecond, cursor());

		// 반영한 이벤트를 다시 평가하지 않는다.
		runAt("2026-11-24T05:02:00.100Z");
		assertEquals(2, sent.size());
		assertEquals(2, assessmentCount());
	}

	@Test
	void futureEventDoesNotBlockLaterEventsAndIsAssessedWhenItsTimeComes() {
		long first = attempt("2026-11-24 04:00:00.000");
		runAt(NOW);
		assertEquals(first, cursor());
		assertEquals(1, sent.size());

		attempt("2026-11-25 05:00:00.000");              // occurred_at이 분석 시각보다 하루 뒤
		long later = attempt("2026-11-24 04:30:00.000"); // 미래 이벤트보다 event_id가 큰 정상 이벤트

		// #63: 미래 이벤트가 있어도 그 뒤의 정상 이벤트는 바로 평가된다. 미래 이벤트는 집계에서 빠진다.
		runAt(NOW);
		assertEquals(2, sent.size());
		assertEquals(2, sent.get(1).features().bookingCount());
		assertEquals(later, cursor());

		// 미래 이벤트의 시각이 되기 전에는 다시 평가하지 않는다.
		runAt("2026-11-24T06:00:00Z");
		assertEquals(2, sent.size());

		// 커서가 event_id로는 이미 지나갔어도, 그 시각이 되면 미래 이벤트가 평가에 반영된다(누락 없음).
		runAt("2026-11-25T05:00:00Z");
		assertEquals(3, sent.size());
		assertEquals(3, sent.get(2).features().bookingCount());
		assertEquals(later, cursor());

		// 한 번 반영한 뒤에는 반복해서 평가하지 않는다.
		runAt("2026-11-25T05:01:00Z");
		assertEquals(3, sent.size());
	}

	@Test
	void cardAndIpFeaturesCountRecentBookingsRegardlessOfJvmTimeZone() {
		// 분석 시각 1시간 전에 같은 결제수단 · 같은 IP로 예매한 계정이 둘이다.
		String other = userService.register(new UserRegistrationRequest(SERVICE, UUID.randomUUID().toString()), SERVICE)
				.user().userId();
		booking(userId, "2026-11-24 04:00:00.000");
		booking(other, "2026-11-24 04:00:00.000");
		LocalDateTime calculatedAt = LocalDateTime.of(2026, 11, 24, 5, 0, 0);

		TimeZone original = TimeZone.getDefault();
		try {
			for (String zone : List.of("Asia/Seoul", "UTC", "America/New_York")) {
				TimeZone.setDefault(TimeZone.getTimeZone(zone));
				var features = featureAggregationService.aggregateFeatures(userId, calculatedAt);
				assertEquals(2, features.getCardReuseCount(), "cardReuseCount (JVM " + zone + ")");
				assertEquals(2, features.getIpBookingDensity(), "ipBookingDensity (JVM " + zone + ")");
			}
		} finally {
			TimeZone.setDefault(original);
		}
	}

	// ---------------------------------------------------------------- #63: 카드 · IP 룰 감점 연동

	@Test
	void bookingPaymentIdentifierFeedsTheCardSharingPenaltyAtItsBoundaries() {
		// 같은 사용자가 같은 결제수단으로 두 번 예매해도 카드는 1행이고, 계정 1개라 감점이 없다.
		pay(SERVICE, userId, "pay-A");
		pay(SERVICE, userId, "pay-A");
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_card WHERE user_id=?", Integer.class, userId));
		// 카드 해시는 SHA-256(serviceId:paymentIdentifier)이고, 백필 SQL(db/manual/063)의 식과 같은 값이다.
		assertEquals(jdbc.queryForObject("SELECT SHA2(CONCAT(?, ':', ?), 256)", String.class, SERVICE, "pay-A"),
				jdbc.queryForObject("SELECT card_hash FROM user_card WHERE user_id=?", String.class, userId));
		assertEquals(50, evaluate());

		// 다른 서비스에서 같은 결제 식별자를 써도 섞이지 않는다.
		pay(OTHER_SERVICE, newUser(OTHER_SERVICE), "pay-A");
		assertEquals(50, evaluate());

		// 같은 서비스의 계정 2개, 3개: 5점 감점
		pay(SERVICE, newUser(SERVICE), "pay-A");
		assertEquals(45, evaluate());
		pay(SERVICE, newUser(SERVICE), "pay-A");
		assertEquals(45, evaluate());

		// 계정 4개: 15점 감점
		pay(SERVICE, newUser(SERVICE), "pay-A");
		assertEquals(35, evaluate());
		assertEquals(15.0, penalty());
	}

	@Test
	void recentAttemptsFromTheSameIpFeedTheIpPenaltyAfterAiAssessment() {
		String ip = "203.0.113.7";
		String other = newUser(SERVICE);
		// 최근 10분(04:50:00 초과 ~ 05:00:00 이하) 같은 IP의 고유 시도 4건: 본인 2건 + 다른 계정 2건
		String firstAttempt = attemptFrom(SERVICE, userId, UUID.randomUUID().toString(), ip, "2026-11-24 04:55:00.000");
		attemptFrom(SERVICE, userId, firstAttempt, ip, "2026-11-24 04:55:01.000"); // 같은 시도의 다음 단계: 1건으로 센다
		attemptFrom(SERVICE, userId, UUID.randomUUID().toString(), ip, "2026-11-24 04:56:00.000");
		attemptFrom(SERVICE, other, UUID.randomUUID().toString(), ip, "2026-11-24 04:57:00.000");
		attemptFrom(SERVICE, other, UUID.randomUUID().toString(), ip, "2026-11-24 04:58:00.000");
		// 세지 않는 것: 10분보다 오래된 시도, 다른 서비스의 같은 IP, 다른 IP, IP가 없는 시도
		attemptFrom(SERVICE, other, UUID.randomUUID().toString(), ip, "2026-11-24 04:50:00.000");
		attemptFrom(OTHER_SERVICE, newUser(OTHER_SERVICE), UUID.randomUUID().toString(), ip, "2026-11-24 04:59:00.000");
		attemptFrom(SERVICE, other, UUID.randomUUID().toString(), "198.51.100.9", "2026-11-24 04:59:00.000");
		String noIpUser = newUser(SERVICE);
		jdbc.update("INSERT INTO behavior_event(user_id,service_id,external_event_id,event_type,occurred_at,metadata) "
				+ "VALUES (?,?,?,'BOOKING_ATTEMPT',?,?)", noIpUser, SERVICE, UUID.randomUUID().toString(),
				"2026-11-24 04:59:00.000", "{\"attemptId\":\"" + UUID.randomUUID() + "\"}");

		LocalDateTime at = LocalDateTime.of(2026, 11, 24, 5, 0, 0);
		assertEquals(4, featureAggregationService.recentIpAttemptCount(userId, at));
		// IP가 실린 시도 이벤트가 없는 사용자는 0이 아니라 null이다(감점 판단 안 함).
		assertEquals(null, featureAggregationService.recentIpAttemptCount(noIpUser, at));

		// 4건: 기준(5회) 미만이라 AI 평가 뒤에도 감점이 없다.
		runAt(NOW);
		assertEquals(50, score());
		assertEquals(0.0, penalty());

		// 5번째 시도가 들어오면 다음 AI 평가 뒤 Credit에 IP 감점 5점이 반영된다.
		attemptFrom(SERVICE, userId, UUID.randomUUID().toString(), ip, "2026-11-24 04:59:30.000");
		runAt("2026-11-24T05:00:01Z");
		assertEquals(5, featureAggregationService.recentIpAttemptCount(userId, at.plusSeconds(1)));
		assertEquals(45, score());
		assertEquals(5.0, penalty());
	}

	@Test
	void eventRegistrationWaitsForTheUserRowLockBeforeAnEventIdIsIssued() throws Exception {
		var request = new EventRequest(SERVICE, UUID.randomUUID().toString(), userId, EventType.BOOKING, "booking", null,
				"2026-11-24T04:00:00Z", Map.of());
		var executor = Executors.newSingleThreadExecutor();
		try {
			var registration = new TransactionTemplate(transactionManager).execute(status -> {
				// 같은 사용자의 다른 이벤트 등록이 진행 중인 상황: user_account 행 잠금을 쥐고 있다.
				userAccountRepository.findByIdForUpdate(userId).orElseThrow();
				var pending = executor.submit(() -> eventService.collect(request, SERVICE));
				assertThrows(TimeoutException.class, () -> pending.get(1, TimeUnit.SECONDS));
				// 기다리는 동안에는 INSERT(= event_id 발급)가 일어나지 않는다.
				verify(behaviorEvents, never()).saveAndFlush(any());
				return pending;
			});
			// 잠금이 풀리면(커밋) 등록이 이어진다.
			assertTrue(registration.get(10, TimeUnit.SECONDS).created());
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM behavior_event WHERE user_id=?", Integer.class, userId));
		} finally {
			executor.shutdownNow();
		}
	}

	// ---------------------------------------------------------------- helpers

	/** 스케줄러가 하는 일을 지정한 시각으로 한 번 실행한다. 이 테스트가 만든 사용자만 평가 대상으로 남긴다. */
	private void runAt(String now) {
		RiskAssessmentCursorRepository ownOnly = mock(RiskAssessmentCursorRepository.class, delegatesTo(cursorRepository));
		doAnswer(invocation -> cursorRepository.findCandidates(invocation.<LocalDateTime>getArgument(0), Integer.MAX_VALUE).stream()
				.filter(candidate -> userId.equals(candidate.getUserId())).toList())
				.when(ownOnly).findCandidates(any(), anyInt());

		AiInferenceClient ai = mock(AiInferenceClient.class);
		when(ai.predictBatch(anyList())).thenAnswer(invocation -> {
			List<AiPredictRequest> requests = invocation.getArgument(0);
			sent.addAll(requests);
			return requests.stream().map(request -> new AiPrediction(request.userId(), "OK", 0.1, 0.2, false,
					"fds-if-v4.0.0", now, null)).toList();
		});

		new RiskAssessmentService(ownOnly, featureAggregationService, ai, userAccountRepository,
				riskAssessmentRepository, eventPublisher, transactionManager,
				Clock.fixed(Instant.parse(now), ZoneOffset.UTC), 100, 500).runOnce();
	}

	/** 예매 시도 이벤트 1건을 지정한 발생 시각(UTC)으로 넣는다. 시도마다 attemptId가 달라 booking_count가 1씩 늘어난다. */
	private long attempt(String occurredAtUtc) {
		String external = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO behavior_event(user_id,service_id,external_event_id,event_type,occurred_at,metadata) "
				+ "VALUES (?,?,?,'BOOKING_ATTEMPT',?,?)", userId, SERVICE, external, occurredAtUtc,
				"{\"attemptId\":\"" + UUID.randomUUID() + "\"}");
		return jdbc.queryForObject("SELECT event_id FROM behavior_event WHERE service_id=? AND external_event_id=?",
				Long.class, SERVICE, external);
	}

	private void booking(String user, String occurredAtUtc) {
		jdbc.update("INSERT INTO behavior_event(user_id,service_id,external_event_id,event_type,booking_id,occurred_at,metadata) "
				+ "VALUES (?,?,?,'BOOKING',?,?,?)", user, SERVICE, UUID.randomUUID().toString(), "b-" + UUID.randomUUID(),
				occurredAtUtc, "{\"paymentIdentifier\":\"pay-shared\",\"ipAddress\":\"203.0.113.7\"}");
	}

	private String newUser(String service) {
		return userService.register(new UserRegistrationRequest(service, UUID.randomUUID().toString()), service)
				.user().userId();
	}

	/** 결제 식별자가 실린 BOOKING 이벤트를 실제 이벤트 등록 경로로 넣는다. */
	private void pay(String service, String user, String paymentIdentifier) {
		String id = UUID.randomUUID().toString();
		eventService.collect(new EventRequest(service, id, user, EventType.BOOKING, "b-" + id, null,
				"2026-11-24T04:00:00Z", Map.of("paymentIdentifier", paymentIdentifier)), service);
	}

	/** IP가 실린 예매 시도 이벤트 1건. 같은 attemptId로 다시 부르면 같은 시도의 다음 단계가 된다. */
	private String attemptFrom(String service, String user, String attemptId, String ip, String occurredAtUtc) {
		jdbc.update("INSERT INTO behavior_event(user_id,service_id,external_event_id,event_type,occurred_at,metadata) "
				+ "VALUES (?,?,?,'BOOKING_ATTEMPT',?,?)", user, service, UUID.randomUUID().toString(), occurredAtUtc,
				"{\"attemptId\":\"" + attemptId + "\",\"ipAddress\":\"" + ip + "\"}");
		return attemptId;
	}

	private int evaluate() {
		return creditEvaluationService.evaluateAndApplyCredit(userId, CreditEvaluationContext.defaultContext()).newScore();
	}

	private int score() {
		return jdbc.queryForObject("SELECT credit_score FROM user_credit WHERE user_id=?", Integer.class, userId);
	}

	private Double penalty() {
		return jdbc.queryForObject("SELECT dynamic_penalty FROM user_credit WHERE user_id=?", Double.class, userId);
	}

	private long cursor() {
		List<Long> rows = jdbc.queryForList("SELECT last_event_id FROM risk_assessment_cursor WHERE user_id=?",
				Long.class, userId);
		assertTrue(rows.size() <= 1);
		return rows.isEmpty() ? 0L : rows.get(0);
	}

	private int assessmentCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM risk_assessment WHERE user_id=?", Integer.class, userId);
	}
}
