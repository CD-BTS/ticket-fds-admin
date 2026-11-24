package com.bts.fairticket.risk;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	private static final String KEY = ApiKeyTestSupport.randomKey();
	private static final String CUTOFF = "2026-11-24T05:00:00.789Z";

	@DynamicPropertySource
	static void keys(DynamicPropertyRegistry registry) {
		registry.add("BTS_API_KEY_HASHES", () -> ApiKeyTestSupport.configuration(Map.of(SERVICE, KEY)));
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private UserService userService;
	@Autowired private EventService eventService;
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
		String ownUsers = "(SELECT user_id FROM user_account WHERE service_id = ?)";
		for (String table : List.of("risk_assessment_cursor", "credit_history", "risk_assessment", "behavior_event",
				"user_credit")) {
			jdbc.update("DELETE FROM " + table + " WHERE user_id IN " + ownUsers, SERVICE);
		}
		jdbc.update("DELETE FROM user_account WHERE service_id = ?", SERVICE);
	}

	@Test
	void eventInTheSameSecondBeforeCutoffIsAssessedAndCursorPassesIt() {
		long eventId = attempt("2026-11-24 05:00:00.500");

		runAt(CUTOFF);

		assertEquals(1, sent.size());
		assertEquals(CUTOFF, sent.get(0).calculatedAt());
		assertEquals(1, sent.get(0).features().bookingCount()); // 같은 초 안의 이벤트가 집계에 들어갔다
		assertEquals(eventId, cursor());
		assertEquals(1, assessmentCount());

		// 새 이벤트가 없으면 다시 평가하지 않는다.
		runAt(CUTOFF);
		assertEquals(1, sent.size());
	}

	@Test
	void cursorStopsBeforeEventAfterCutoffAndThatEventIsAssessedOnceItsTimeHasPassed() {
		long before = attempt("2026-11-24 05:00:00.500");
		long after = attempt("2026-11-24 05:00:00.900"); // 같은 초지만 cutoff(.789)보다 뒤

		runAt(CUTOFF);

		assertEquals(1, sent.size());
		assertEquals(1, sent.get(0).features().bookingCount()); // cutoff 이후 이벤트는 집계에서 빠진다
		assertEquals(after - 1, cursor());                       // 그래서 커서도 그 직전에서 멈춘다
		assertTrue(cursor() >= before);

		// 같은 시각으로 다시 실행하면 옮길 위치가 없으므로 후보가 아니다.
		runAt(CUTOFF);
		assertEquals(1, sent.size());

		// 시각이 지나면 다시 후보가 되고, 이번에는 두 이벤트가 모두 반영된다.
		runAt("2026-11-24T05:00:01Z");
		assertEquals(2, sent.size());
		assertEquals(2, sent.get(1).features().bookingCount());
		assertEquals(after, cursor());
	}

	@Test
	void futureEventIsNotSkippedEvenWhenANormalEventFollowsIt() {
		long first = attempt("2026-11-24 04:00:00.000");
		runAt(CUTOFF);
		assertEquals(first, cursor());

		long future = attempt("2026-11-25 05:00:00.000"); // occurred_at이 분석 시각보다 하루 뒤
		long later = attempt("2026-11-24 04:30:00.000");  // 미래 이벤트보다 event_id가 큰 정상 이벤트

		runAt(CUTOFF);

		// 정상 이벤트(later)가 뒤에 있어도 커서는 미래 이벤트를 넘어가지 않는다.
		assertTrue(cursor() < future, "cursor must stay before the future-dated event");

		// 미래 이벤트의 시각이 지난 뒤에는 세 이벤트가 모두 반영되고 커서가 끝까지 간다.
		runAt("2026-11-25T05:00:00Z");
		assertEquals(3, sent.get(sent.size() - 1).features().bookingCount());
		assertEquals(later, cursor());
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
		doAnswer(invocation -> cursorRepository.findCandidates(invocation.<java.time.LocalDateTime>getArgument(0), Integer.MAX_VALUE).stream()
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
