package com.bts.fairticket.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.bts.fairticket.credit.dto.CreditEvaluationContext;
import com.bts.fairticket.credit.service.CreditEvaluationService;
import com.bts.fairticket.risk.client.AiInferenceClient;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPredictRequest;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPrediction;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.repository.RiskAssessmentAttemptRepository;
import com.bts.fairticket.risk.repository.RiskAssessmentCursorRepository;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.risk.service.FeatureAggregationService;
import com.bts.fairticket.risk.service.RiskAssessmentService;
import com.bts.fairticket.user.ApiKeyTestSupport;
import com.bts.fairticket.user.dto.UserRegistrationRequest;
import com.bts.fairticket.user.repository.UserAccountRepository;
import com.bts.fairticket.user.service.UserService;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #67: 평가 시도 이력 · 평가 당시 Feature · Credit 산출 근거 · 정책 적용 기록의 저장과,
 * 그것을 읽는 관리자 조회 API를 실제 MySQL로 검증한다. AI 서버만 가짜로 둔다.
 * 실행할 때마다 만든 임의 서비스의 데이터만 만들고 지운다.
 */
@Tag("mysql")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureMockMvc
class AdminQueryStorageMySqlTests {
	private static final String SERVICE = "aq-" + UUID.randomUUID();
	private static final String OTHER = "aq-" + UUID.randomUUID();
	private static final String KEY = ApiKeyTestSupport.randomKey();
	private static final String OTHER_KEY = ApiKeyTestSupport.randomKey();
	private static final String ADMIN = ApiKeyTestSupport.randomKey();
	private static final String OTHER_ADMIN = ApiKeyTestSupport.randomKey();
	private static final String MODEL = "fds-if-v4.0.0";

	@DynamicPropertySource
	static void keys(DynamicPropertyRegistry registry) {
		registry.add("BTS_API_KEY_HASHES", () -> ApiKeyTestSupport.configuration(Map.of(SERVICE, KEY, OTHER, OTHER_KEY)));
		registry.add("BTS_ADMIN_API_KEYS", () -> "[{\"hash\":\"" + ApiKeyTestSupport.hash(ADMIN)
				+ "\",\"services\":[\"" + SERVICE + "\"],\"allServices\":false},{\"hash\":\""
				+ ApiKeyTestSupport.hash(OTHER_ADMIN) + "\",\"services\":[\"" + OTHER + "\"],\"allServices\":false}]");
	}

	@Autowired private MockMvc mvc;
	@Autowired private JsonMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private UserService userService;
	@Autowired private UserAccountRepository userAccountRepository;
	@Autowired private RiskAssessmentRepository riskAssessmentRepository;
	@Autowired private RiskAssessmentAttemptRepository riskAssessmentAttemptRepository;
	@Autowired private RiskAssessmentCursorRepository cursorRepository;
	@Autowired private FeatureAggregationService featureAggregationService;
	@Autowired private CreditEvaluationService creditEvaluationService;
	@Autowired private ApplicationEventPublisher eventPublisher;
	@Autowired private PlatformTransactionManager transactionManager;
	private String userId;

	@BeforeEach
	void createOwnUser() {
		userId = newUser(SERVICE);
	}

	@AfterEach
	void cleanOnlyOwnRandomServiceFixtures() {
		String ownUsers = "(SELECT user_id FROM user_account WHERE service_id IN (?, ?))";
		for (String table : List.of("policy_application", "policy_decision", "risk_assessment_attempt",
				"risk_assessment_cursor", "credit_history", "credit_calculation", "booking_history", "user_card",
				"risk_assessment", "behavior_event", "user_credit")) {
			jdbc.update("DELETE FROM " + table + " WHERE user_id IN " + ownUsers, SERVICE, OTHER);
		}
		jdbc.update("DELETE FROM user_account WHERE service_id IN (?, ?)", SERVICE, OTHER);
		jdbc.update("DELETE FROM concert WHERE service_id IN (?, ?)", SERVICE, OTHER);
	}

	// ---------------------------------------------------------------- D-2, D-3: 평가 당시 Feature와 시도 이력

	@Test
	void assessmentKeepsSentFeaturesAndUnscoredAttemptsDoNotOverwriteScoreOrCredit() throws Exception {
		attempt(userId, "c-1", "a1", "STARTED", "ENTERED", 1, "2026-11-24 04:00:00.000");
		attempt(userId, "c-1", "a2", "STARTED", "ENTERED", 1, "2026-11-24 04:00:30.000");

		runAi("2026-11-24T05:00:00Z", Map.of(userId, "OK:0.75"));

		// 성공한 평가: AI에 보낸 Feature가 평가 행에 남고, 시도 행은 평가를 가리키기만 한다(Feature 중복 저장 없음).
		var assessment = jdbc.queryForMap("SELECT * FROM risk_assessment WHERE user_id=?", userId);
		assertEquals(2, assessment.get("booking_count"));
		assertEquals(30.0, (Double) assessment.get("avg_booking_interval"), 1e-9);
		assertNull(assessment.get("booking_interval_variance")); // 간격 5개 미만: AI에 null로 보냈고 null로 남는다
		assertNull(assessment.get("cancel_rate"));
		assertEquals(1, assessment.get("card_reuse_count"));
		var okAttempt = jdbc.queryForMap("SELECT * FROM risk_assessment_attempt WHERE user_id=?", userId);
		assertEquals("OK", okAttempt.get("status"));
		assertEquals(assessment.get("assessment_id"), okAttempt.get("assessment_id"));
		assertNull(okAttempt.get("booking_count"));
		assertEquals(30, score()); // 50 - 40 * (2 * 0.75 - 1)
		int creditHistories = count("credit_history");

		// 데이터 부족과 INVALID: 점수 없이 시도 이력만 남는다. 기존 점수와 Credit은 그대로다.
		attempt(userId, "c-1", "a3", "STARTED", "ENTERED", 1, "2026-11-24 05:00:30.000");
		runAi("2026-11-24T05:01:00Z", Map.of(userId, "INSUFFICIENT_DATA"));
		attempt(userId, "c-1", "a4", "STARTED", "ENTERED", 1, "2026-11-24 05:01:30.000");
		runAi("2026-11-24T05:02:00Z", Map.of(userId, "INVALID"));

		assertEquals(1, count("risk_assessment"));
		assertEquals(3, count("risk_assessment_attempt"));
		assertEquals(30, score());
		assertEquals(creditHistories, count("credit_history"));
		var insufficient = jdbc.queryForMap(
				"SELECT * FROM risk_assessment_attempt WHERE user_id=? AND status='INSUFFICIENT_DATA'", userId);
		assertNull(insufficient.get("assessment_id"));
		assertEquals(3, insufficient.get("booking_count")); // 그 시도에서 AI에 보낸 값
		assertNotNull(insufficient.get("reason"));

		// 최신 성공 평가(0.75)와 최신 시도 상태(INVALID)를 구분해서 돌려준다.
		String never = newUser(SERVICE);
		admin(get("/api/v1/admin/users/" + userId))
				.andExpect(jsonPath("$.data.user.anomalyScore").value(0.75))
				.andExpect(jsonPath("$.data.assessmentStatus").value("INVALID"))
				.andExpect(jsonPath("$.data.latestAttempt.status").value("INVALID"))
				.andExpect(jsonPath("$.data.latestAttempt.anomalyScore").value(nullValue()))
				.andExpect(jsonPath("$.data.latestAttempt.reason").value("features.bookingCount는 null일 수 없습니다."))
				.andExpect(jsonPath("$.data.latestAttempt.features.bookingCount").value(4))
				.andExpect(jsonPath("$.data.latestAttempt.calculatedAt").value("2026-11-24T05:02:00Z"))
				.andExpect(jsonPath("$.data.expectedPolicy").value("DELAY")) // Credit 30, 점수 0.75
				.andExpect(jsonPath("$.data.dynamicPenalty").value(20.0))
				.andExpect(jsonPath("$.data.latestCalculation.aiPenalty").value(20.0))
				.andExpect(jsonPath("$.data.latestCalculation.kind").value("EVALUATION"));
		admin(get("/api/v1/admin/users/" + never))
				.andExpect(jsonPath("$.data.user.anomalyScore").value(nullValue()))
				.andExpect(jsonPath("$.data.assessmentStatus").value("NOT_EVALUATED"))
				.andExpect(jsonPath("$.data.latestAttempt").value(nullValue()))
				.andExpect(jsonPath("$.data.latestCalculation").value(nullValue()));
		admin(get("/api/v1/admin/users/" + userId + "/assessments"))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].anomalyScore").value(0.75))
				.andExpect(jsonPath("$.data.content[0].features.bookingCount").value(2))
				.andExpect(jsonPath("$.data.content[0].features.bookingIntervalVariance").value(nullValue()));
		admin(get("/api/v1/admin/users/" + userId + "/assessment-attempts"))
				.andExpect(jsonPath("$.data.totalElements").value(3))
				.andExpect(jsonPath("$.data.content[0].status").value("INVALID"))
				.andExpect(jsonPath("$.data.content[1].status").value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.data.content[1].anomalyScore").value(nullValue()))
				.andExpect(jsonPath("$.data.content[2].status").value("OK"))
				.andExpect(jsonPath("$.data.content[2].anomalyScore").value(0.75))
				.andExpect(jsonPath("$.data.content[2].modelVersion").value(MODEL))
				.andExpect(jsonPath("$.data.content[2].features.bookingCount").value(2));
		// 정책 판정은 여전히 최신 성공 평가의 점수를 쓴다.
		service(post("/api/v1/users/" + userId + "/policy-decisions").contentType("application/json")
				.content("{\"context\":\"QUEUE_JOIN\"}"), KEY)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.action").value("DELAY"))
				.andExpect(jsonPath("$.data.anomalyScore").value(0.75));
		// 다른 서비스의 관리자는 볼 수 없다.
		mvc.perform(get("/api/v1/admin/users/" + userId + "/assessment-attempts").header("X-API-Key", OTHER_ADMIN))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- D-1: Credit 산출 근거

	@Test
	void creditCalculationExplainsTheScoreAndAttendanceChangesPointToThePenaltySource() throws Exception {
		var user = userAccountRepository.findById(userId).orElseThrow();
		long assessmentId = riskAssessmentRepository.saveAndFlush(new RiskAssessment(user, 0.75, MODEL)).getAssessmentId();
		// #67 이전에 남은 이력: 산출 근거가 없다.
		jdbc.update("INSERT INTO credit_history(user_id,score_before,score_after,reason) VALUES (?,50,50,'이전 평가')", userId);

		// AI 감점 40 * (2 * 0.75 - 1) = 20, 룰 감점 IP 5 + 분산 5 = 10 → 50 - 30 = 20
		var context = new CreditEvaluationContext("정기 평가", 5, 0.5);
		assertEquals(20, creditEvaluationService.evaluateAndApplyCredit(userId, context).newScore());
		var first = jdbc.queryForMap("SELECT * FROM credit_calculation WHERE user_id=?", userId);
		assertEquals("EVALUATION", first.get("kind"));
		assertEquals(assessmentId, ((Number) first.get("assessment_id")).longValue());
		assertEquals(0.75, first.get("anomaly_score"));
		assertEquals(5, first.get("recent_ip_attempt_count"));
		assertEquals(0.5, first.get("interval_variance"));
		assertEquals(0, first.get("card_shared_accounts"));
		assertEquals(50.0, first.get("base_credit"));
		assertEquals(20.0, first.get("ai_penalty"));
		assertEquals(0.0, first.get("card_penalty"));
		assertEquals(5.0, first.get("ip_penalty"));
		assertEquals(5.0, first.get("variance_penalty"));
		assertEquals(10.0, first.get("rule_penalty_raw"));
		assertEquals(10.0, first.get("rule_penalty"));
		assertEquals(30.0, first.get("dynamic_penalty"));
		assertEquals(20.0, first.get("raw_credit"));
		assertEquals(50, first.get("score_before"));
		assertEquals(20, first.get("score_after"));
		assertEquals(first.get("calculation_id"), latestCalculationId());

		// 점수가 그대로여도 최신 산출 근거는 다시 기록된다.
		assertEquals(20, creditEvaluationService.evaluateAndApplyCredit(userId, context).newScore());
		assertEquals(2, count("credit_calculation"));
		Object evaluationId = latestCalculationId();
		assertTrue(((Number) evaluationId).longValue() > ((Number) first.get("calculation_id")).longValue());

		// 관람 완료: 저장된 동적 감점(30)을 재사용한다. 감점 상세를 새로 만들지 않고 원본 평가를 가리킨다.
		String booking = "b_" + UUID.randomUUID();
		String ticketA = "t_" + UUID.randomUUID();
		String ticketB = "t_" + UUID.randomUUID();
		event("BOOKING", booking, null, "2026-10-01T09:00:00Z", performance(List.of(ticketA, ticketB)));
		event("CHECK_IN", booking, ticketA, "2026-10-10T09:45:00Z", performance(null));
		var attendance = jdbc.queryForMap("SELECT * FROM credit_calculation WHERE calculation_id=?", latestCalculationId());
		assertEquals("ATTENDANCE", attendance.get("kind"));
		assertEquals(evaluationId, attendance.get("source_calculation_id"));
		assertNull(attendance.get("ai_penalty"));
		assertNull(attendance.get("rule_penalty"));
		assertEquals(30.0, attendance.get("dynamic_penalty"));
		assertEquals(1, attendance.get("completed_count"));
		assertEquals(100.0 * 6 / 11, (Double) attendance.get("base_credit"), 1e-9);
		assertEquals(100.0 * 6 / 11 - 30, (Double) attendance.get("raw_credit"), 1e-9);
		assertEquals(25, attendance.get("score_after")); // 24.5 → 25
		assertEquals(25, score());
		// 관람 이력이 다시 바뀌어도 원본 평가를 계속 가리킨다.
		event("CHECK_IN", booking, ticketB, "2026-10-10T09:46:00Z", performance(null));
		assertEquals(evaluationId, jdbc.queryForObject(
				"SELECT source_calculation_id FROM credit_calculation WHERE calculation_id=?", Object.class, latestCalculationId()));

		// 관리자 Credit 이력: 최신순, UTC 표기, 산출 근거 포함. 이전 이력은 근거가 null이다(다시 계산하지 않는다).
		admin(get("/api/v1/admin/users/" + userId + "/credit-history").param("size", "10"))
				.andExpect(jsonPath("$.data.totalElements").value(5))
				.andExpect(jsonPath("$.data.content[0].calculation.kind").value("ATTENDANCE"))
				.andExpect(jsonPath("$.data.content[0].calculation.aiPenalty").value(nullValue()))
				.andExpect(jsonPath("$.data.content[0].createdAt").value(endsWith("Z")))
				.andExpect(jsonPath("$.data.content[2].calculation.kind").value("EVALUATION"))
				.andExpect(jsonPath("$.data.content[2].calculation.dynamicPenalty").value(30.0))
				.andExpect(jsonPath("$.data.content[3].scoreAfter").value(20))
				.andExpect(jsonPath("$.data.content[3].calculation.rulePenaltyRaw").value(10.0))
				.andExpect(jsonPath("$.data.content[4].reason").value("이전 평가"))
				.andExpect(jsonPath("$.data.content[4].calculation").value(nullValue()));
		mvc.perform(get("/api/v1/admin/users/" + userId + "/credit-history").header("X-API-Key", OTHER_ADMIN))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/admin/users/" + userId + "/credit-history").header("X-API-Key", KEY))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- 기존 Credit API 인증과 시각 표기

	@Test
	void creditApiRequiresAServiceKeyAndHidesOtherServicesUsers() throws Exception {
		creditEvaluationService.evaluateAndApplyCredit(userId, CreditEvaluationContext.defaultContext());
		for (String path : List.of("/credit", "/credit-history")) {
			String url = "/api/v1/users/" + userId + path;
			// 임의의 Bearer 값만으로는 더 이상 조회할 수 없다.
			mvc.perform(get(url).header("Authorization", "Bearer anything")).andExpect(status().isUnauthorized());
			mvc.perform(get(url).header("X-API-Key", "wrong-key")).andExpect(status().isUnauthorized());
			mvc.perform(get(url).header("X-API-Key", OTHER_KEY)).andExpect(status().isNotFound());
			mvc.perform(get(url).header("X-API-Key", ADMIN)).andExpect(status().isForbidden());
		}
		service(get("/api/v1/users/" + userId + "/credit"), KEY).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.creditScore").value(50))
				.andExpect(jsonPath("$.data.updatedAt").value(endsWith("Z")));
		service(get("/api/v1/users/" + userId + "/credit-history"), KEY).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.histories[0].createdAt").value(endsWith("Z")));
	}

	// ---------------------------------------------------------------- D-4: 정책 결정의 공연과 실제 적용 기록

	@Test
	void policyApplicationsAreValidatedIdempotentAndCountedPerDecision() throws Exception {
		String other = newUser(SERVICE);
		String foreign = newUser(OTHER);
		jdbc.update("UPDATE user_credit SET credit_score=30 WHERE user_id IN (?, ?)", userId, other); // 판정 결과를 CAPTCHA로 고정
		concert("c-1", "Rock Night");
		concert("c-2", "Jazz Day");
		attempt(userId, "c-1", "a1", "STARTED", "ENTERED", 1, "2026-11-24 04:00:00.000");
		attempt(other, "c-1", "a2", "STARTED", "ENTERED", 1, "2026-11-24 04:00:10.000");
		int events = count("behavior_event");

		// 정책 결정: 공연 ID는 선택이다. 기존 형식(공연 ID 없음)도 그대로 받는다.
		long withConcert = decide(userId, "{\"context\":\"QUEUE_JOIN\",\"concertId\":\"c-1\"}", "c-1");
		long withoutConcert = decide(userId, "{\"context\":\"BOOKING_ATTEMPT\"}", null);
		long othersDecision = decide(other, "{\"context\":\"QUEUE_JOIN\",\"concertId\":\"c-1\"}", "c-1");
		assertEquals(SERVICE, jdbc.queryForObject("SELECT service_id FROM policy_decision WHERE decision_id=?",
				String.class, withConcert));

		// 적용 기록: 생성 201, 같은 내용 재전송 200(같은 행), 같은 키에 다른 내용 409
		String key1 = UUID.randomUUID().toString();
		long applicationId = mapper.readTree(apply(key1, userId, withConcert, "c-1", "REQUIRE_CAPTCHA", "CHALLENGED")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.appliedAt").value("2026-11-24T04:05:00.123Z"))
				.andReturn().getResponse().getContentAsString()).get("data").get("applicationId").asLong();
		apply(key1, userId, withConcert, "c-1", "REQUIRE_CAPTCHA", "CHALLENGED").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.applicationId").value(applicationId));
		apply(key1, userId, withConcert, "c-1", "REQUIRE_CAPTCHA", "PASSED").andExpect(status().isConflict());
		// 같은 결정의 통과 기록, 공연 ID가 없던 결정의 적용(적용 기록의 공연을 쓴다), 다른 사용자의 적용
		apply(UUID.randomUUID().toString(), userId, withConcert, "c-1", "REQUIRE_CAPTCHA", "PASSED").andExpect(status().isCreated());
		apply(UUID.randomUUID().toString(), userId, withoutConcert, "c-1", "REQUIRE_CAPTCHA", "CHALLENGED").andExpect(status().isCreated());
		apply(UUID.randomUUID().toString(), other, othersDecision, "c-1", "REQUIRE_CAPTCHA", "FAILED").andExpect(status().isCreated());

		// 정합성 검증
		apply(UUID.randomUUID().toString(), userId, withConcert, "c-1", "DELAY", "BLOCKED").andExpect(status().isBadRequest()); // 허용하지 않는 조합
		apply(UUID.randomUUID().toString(), userId, withConcert, "c-1", "ALLOW", "PASSED").andExpect(status().isBadRequest());  // ALLOW는 제재가 아니다
		apply(UUID.randomUUID().toString(), userId, withConcert, "c-1", "DELAY", "DELAYED").andExpect(status().isConflict());   // 결정은 CAPTCHA였다
		apply(UUID.randomUUID().toString(), userId, withConcert, "c-2", "REQUIRE_CAPTCHA", "CHALLENGED").andExpect(status().isConflict()); // 결정의 공연과 다름
		apply(UUID.randomUUID().toString(), userId, othersDecision, "c-1", "REQUIRE_CAPTCHA", "CHALLENGED").andExpect(status().isNotFound()); // 다른 사용자의 결정
		apply(UUID.randomUUID().toString(), foreign, withConcert, "c-1", "REQUIRE_CAPTCHA", "CHALLENGED").andExpect(status().isNotFound()); // 다른 서비스의 사용자
		mvc.perform(post("/api/v1/policy-applications").header("X-API-Key", OTHER_KEY).contentType("application/json")
				.content(application(UUID.randomUUID().toString(), SERVICE, userId, withConcert, "c-1", "REQUIRE_CAPTCHA",
						"CHALLENGED", "2026-11-24T04:05:00.123Z"))).andExpect(status().isForbidden()); // 다른 서비스의 키
		mvc.perform(post("/api/v1/policy-applications").header("X-API-Key", OTHER_KEY).contentType("application/json")
				.content(application(UUID.randomUUID().toString(), OTHER, userId, withConcert, "c-1", "REQUIRE_CAPTCHA",
						"CHALLENGED", "2026-11-24T04:05:00.123Z"))).andExpect(status().isNotFound()); // 자기 서비스로 남의 사용자
		mvc.perform(post("/api/v1/policy-applications").header("X-API-Key", KEY).contentType("application/json")
				.content(application(UUID.randomUUID().toString(), SERVICE, userId, withConcert, "c-1", "REQUIRE_CAPTCHA",
						"CHALLENGED", "2026-11-24T04:05:00"))).andExpect(status().isBadRequest()); // 오프셋 없는 시각
		assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM policy_application WHERE service_id=?", Integer.class, SERVICE));
		// 정책 적용 기록은 행동 이벤트가 아니다: AI 평가 대상과 Feature에 영향을 주지 않는다.
		assertEquals(events, count("behavior_event"));

		// 공연 통계: 실제 적용은 고유 사용자와 고유 결정으로 세고, 예상 제재 대상과 구분한다.
		admin(get("/api/v1/admin/concerts/c-1").param("serviceId", SERVICE))
				.andExpect(jsonPath("$.data.sanctions.recorded").value(true))
				.andExpect(jsonPath("$.data.sanctions.appliedUserCount").value(2))
				.andExpect(jsonPath("$.data.sanctions.byPolicy.REQUIRE_CAPTCHA.userCount").value(2))
				// 결정 3건. CHALLENGED와 PASSED가 모두 기록된 결정도 1회다.
				.andExpect(jsonPath("$.data.sanctions.byPolicy.REQUIRE_CAPTCHA.applicationCount").value(3))
				.andExpect(jsonPath("$.data.sanctions.byPolicy.DELAY.applicationCount").value(0))
				.andExpect(jsonPath("$.data.sanctions.captcha.challenged").value(3))
				.andExpect(jsonPath("$.data.sanctions.captcha.passed").value(1))
				.andExpect(jsonPath("$.data.sanctions.captcha.failed").value(1))
				.andExpect(jsonPath("$.data.expectedSanctionUserCount").value(2))
				.andExpect(jsonPath("$.data.usersByExpectedPolicy.REQUIRE_CAPTCHA").value(2));
		// 기록을 보내는 서비스의 다른 공연: 실제로 0건이다.
		admin(get("/api/v1/admin/concerts/c-2").param("serviceId", SERVICE))
				.andExpect(jsonPath("$.data.sanctions.recorded").value(true))
				.andExpect(jsonPath("$.data.sanctions.appliedUserCount").value(0));
		admin(get("/api/v1/admin/summary").param("serviceId", SERVICE))
				.andExpect(jsonPath("$.data.sanctions.appliedUserCount").value(2))
				.andExpect(jsonPath("$.data.expectedSanctionUsers").value(2));
		// 기록을 보낸 적 없는 서비스: 0이 아니라 "기록 없음"이다.
		mvc.perform(get("/api/v1/admin/summary").param("serviceId", OTHER).header("X-API-Key", OTHER_ADMIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.sanctions.recorded").value(false))
				.andExpect(jsonPath("$.data.sanctions.appliedUserCount").value(nullValue()));
		// 추이: 결정 3건이 첫 적용 시각(04:05)에 한 번씩 잡힌다.
		admin(get("/api/v1/admin/concerts/c-1/trends").param("serviceId", SERVICE)
				.param("from", "2026-11-24T04:05:00Z").param("to", "2026-11-24T04:06:00Z"))
				.andExpect(jsonPath("$.data.sanctionRecorded").value(true))
				.andExpect(jsonPath("$.data.buckets", hasSize(1)))
				.andExpect(jsonPath("$.data.buckets[0].sanctionAppliedCount").value(3));
	}

	// ---------------------------------------------------------------- 사용자 검색 · 정렬

	@Test
	void usersAreSearchedFilteredAndSortedByLatestAnomalyScoreInTheDatabase() throws Exception {
		String high = userId;
		String low = newUser(SERVICE);
		String none = newUser(SERVICE);
		String foreign = newUser(OTHER);
		assess(high, 0.20); // 이전 평가. 최신 평가(0.90)만 조건과 정렬에 쓰인다.
		assess(high, 0.90);
		assess(low, 0.30);
		assess(foreign, 0.99);

		// 미평가 사용자는 0점으로 취급하지 않고, 오름차순 · 내림차순 모두 뒤에 둔다.
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("sort", "anomalyScore,desc"))
				.andExpect(jsonPath("$.data.totalElements").value(3))
				.andExpect(jsonPath("$.data.content[0].userId").value(high))
				.andExpect(jsonPath("$.data.content[0].anomalyScore").value(0.9))
				.andExpect(jsonPath("$.data.content[0].assessmentStatus").value("OK"))
				.andExpect(jsonPath("$.data.content[1].userId").value(low))
				.andExpect(jsonPath("$.data.content[2].userId").value(none))
				.andExpect(jsonPath("$.data.content[2].anomalyScore").value(nullValue()))
				.andExpect(jsonPath("$.data.content[2].assessmentStatus").value("NOT_EVALUATED"));
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("sort", "anomalyScore,asc"))
				.andExpect(jsonPath("$.data.content[0].userId").value(low))
				.andExpect(jsonPath("$.data.content[1].userId").value(high))
				.andExpect(jsonPath("$.data.content[2].userId").value(none));
		// 점수 범위: 미평가 사용자는 빠진다. 페이지 수와 전체 건수도 조건 기준이다.
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("minAnomalyScore", "0.5"))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].userId").value(high));
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("maxAnomalyScore", "0.5"))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].userId").value(low));
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("assessed", "false"))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].userId").value(none));
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("assessed", "true")
				.param("sort", "anomalyScore,desc").param("size", "1").param("page", "1"))
				.andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.content[0].userId").value(low));
		// 부분 검색: 내부 ID의 일부. LIKE 특수문자는 그대로의 문자로 찾는다.
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("keyword", low.substring(4, 20)))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].userId").value(low));
		admin(get("/api/v1/admin/users").param("serviceId", SERVICE).param("keyword", "%"))
				.andExpect(jsonPath("$.data.totalElements").value(0));
		mvc.perform(get("/api/v1/admin/users").param("serviceId", SERVICE).param("minAnomalyScore", "0.8")
				.param("maxAnomalyScore", "0.2").header("X-API-Key", ADMIN)).andExpect(status().isBadRequest());
		// 다른 서비스의 사용자는 어떤 조건으로도 나오지 않는다.
		admin(get("/api/v1/admin/users").param("minAnomalyScore", "0.95"))
				.andExpect(jsonPath("$.data.totalElements").value(0));
		mvc.perform(get("/api/v1/admin/users").param("serviceId", OTHER).header("X-API-Key", ADMIN))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- 요약 · 공연 통계 · 추이

	@Test
	void summaryConcertStatisticsAndTrendsKeepTheirUnitsApart() throws Exception {
		String u1 = userId;
		String u2 = newUser(SERVICE);
		String u3 = newUser(SERVICE);
		String outsider = newUser(SERVICE); // 이 공연에 참여하지 않은 이상 사용자
		concert("c-1", "Rock Night");
		concert("c-2", "Jazz Day");
		// u1 시도 A: 성공. 같은 단계 이벤트가 중복으로 와도 시도는 1건이다.
		attempt(u1, "c-1", "A", "STARTED", "ENTERED", 1, "2026-11-24 05:00:10.000");
		attempt(u1, "c-1", "A", "SEAT_HOLD", "SUCCEEDED", 2, "2026-11-24 05:00:20.000");
		attempt(u1, "c-1", "A", "COMPLETED", "SUCCEEDED", 3, "2026-11-24 05:00:30.000");
		attempt(u1, "c-1", "A", "SEAT_HOLD", "SUCCEEDED", 2, "2026-11-24 05:00:21.000");
		// u1 시도 B: 좌석 선점 거절로 끝남. u2 시도 C: 결제 단계에서 중단. u3 시도 D: 아직 진행 중.
		attempt(u1, "c-1", "B", "STARTED", "ENTERED", 1, "2026-11-24 05:00:50.000");
		attempt(u1, "c-1", "B", "SEAT_HOLD", "REJECTED", 2, "2026-11-24 05:00:55.000");
		attempt(u2, "c-1", "C", "STARTED", "ENTERED", 1, "2026-11-24 05:01:10.000");
		attempt(u2, "c-1", "C", "PAYMENT", "ABANDONED", 2, "2026-11-24 05:01:40.000");
		attempt(u3, "c-1", "D", "STARTED", "ENTERED", 1, "2026-11-24 05:03:00.000");
		attempt(u3, "c-1", "D", "QUEUE", "SUCCEEDED", 2, "2026-11-24 05:03:05.000");
		// 시작이 확인되지 않은 시도와 다른 공연의 시도는 이 공연의 모집단이 아니다.
		attempt(u3, "c-1", "E", "QUEUE", "ENTERED", 2, "2026-11-24 05:03:10.000");
		attempt(outsider, "c-2", "F", "STARTED", "ENTERED", 1, "2026-11-24 05:00:00.000");
		String booking = "b_" + UUID.randomUUID();
		rawEvent(u1, "BOOKING", booking, "2026-11-24 05:00:30.000", "{\"concertId\":\"c-1\"}");
		rawEvent(u1, "CANCEL", booking, "2026-11-24 05:10:00.000", "{\"concertId\":\"c-1\"}");
		long before = assess(u1, 0.95);  // 이 공연에 참여하기 전의 평가
		long during = assess(u1, 0.90);  // 참여 이후의 이상 평가 (u1의 최신 평가)
		long normal = assess(u2, 0.30);
		assess(outsider, 0.99);
		jdbc.update("UPDATE risk_assessment SET assessed_at='2026-11-24 04:59:00' WHERE assessment_id=?", before);
		jdbc.update("UPDATE risk_assessment SET assessed_at='2026-11-24 05:02:30' WHERE assessment_id=?", during);
		jdbc.update("UPDATE risk_assessment SET assessed_at='2026-11-24 05:02:40' WHERE assessment_id=?", normal);

		admin(get("/api/v1/admin/concerts/c-1").param("serviceId", SERVICE))
				.andExpect(jsonPath("$.data.bookingAttemptCount").value(4))      // 고유 시도
				.andExpect(jsonPath("$.data.bookingAttemptUserCount").value(3))  // 고유 사용자
				.andExpect(jsonPath("$.data.bookingSuccessCount").value(1))
				.andExpect(jsonPath("$.data.bookingFailureCount").value(2))
				.andExpect(jsonPath("$.data.bookingInProgressCount").value(1))
				.andExpect(jsonPath("$.data.failureBreakdown['SEAT_HOLD/REJECTED']").value(1))
				.andExpect(jsonPath("$.data.failureBreakdown['PAYMENT/ABANDONED']").value(1))
				.andExpect(jsonPath("$.data.bookingCount").value(1))             // 고유 예매
				.andExpect(jsonPath("$.data.cancelCount").value(1))
				.andExpect(jsonPath("$.data.checkInCount").value(0))             // 티켓
				.andExpect(jsonPath("$.data.assessedUserCount").value(2))
				.andExpect(jsonPath("$.data.unassessedUserCount").value(1))      // u3: 이상에도 정상에도 넣지 않는다
				.andExpect(jsonPath("$.data.anomalyUserCount").value(1))
				.andExpect(jsonPath("$.data.averageCreditScore").value(50.0))
				.andExpect(jsonPath("$.data.usersByExpectedPolicy.DELAY").value(1))   // u1: 점수 0.9
				.andExpect(jsonPath("$.data.usersByExpectedPolicy.ALLOW").value(2))
				.andExpect(jsonPath("$.data.expectedSanctionUserCount").value(1))
				// 적용 기록을 보낸 적 없는 서비스: 0이 아니라 기록 없음
				.andExpect(jsonPath("$.data.sanctions.recorded").value(false))
				.andExpect(jsonPath("$.data.sanctions.appliedUserCount").value(nullValue()));

		// 공연 목록: 이름 검색은 DB에서 걸러 전체 건수에 반영되고, 공연별 이상 사용자 수가 붙는다.
		admin(get("/api/v1/admin/concerts").param("serviceId", SERVICE).param("name", "rock"))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].concertId").value("c-1"))
				.andExpect(jsonPath("$.data.content[0].anomalyUserCount").value(1));
		admin(get("/api/v1/admin/concerts").param("serviceId", SERVICE).param("sort", "concertId,asc"))
				.andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.content[1].concertId").value("c-2"))
				.andExpect(jsonPath("$.data.content[1].anomalyUserCount").value(1)); // outsider
		mvc.perform(get("/api/v1/admin/concerts").param("serviceId", SERVICE).header("X-API-Key", OTHER_ADMIN))
				.andExpect(status().isForbidden());
		// 공연별 사용자: 같은 검색 · 정렬 조건을 쓴다.
		admin(get("/api/v1/admin/concerts/c-1/users").param("serviceId", SERVICE).param("sort", "anomalyScore,desc"))
				.andExpect(jsonPath("$.data.totalElements").value(3))
				.andExpect(jsonPath("$.data.content[0].user.userId").value(u1))
				.andExpect(jsonPath("$.data.content[2].user.userId").value(u3));

		// 요약: 평가 완료 · 미평가 · 이상탐지 · 등급 비율 · 예상 정책
		admin(get("/api/v1/admin/summary").param("serviceId", SERVICE))
				.andExpect(jsonPath("$.data.totalUsers").value(4))
				.andExpect(jsonPath("$.data.assessedUsers").value(3))
				.andExpect(jsonPath("$.data.unassessedUsers").value(1))
				.andExpect(jsonPath("$.data.anomalyUsers").value(2))
				.andExpect(jsonPath("$.data.insufficientDataUsers").value(0))
				.andExpect(jsonPath("$.data.usersByTierRatio.BASE").value(1.0))
				.andExpect(jsonPath("$.data.usersByExpectedPolicy.DELAY").value(2))
				.andExpect(jsonPath("$.data.usersByExpectedPolicy.ALLOW").value(2))
				.andExpect(jsonPath("$.data.expectedSanctionUsers").value(2));

		// 추이: 60초 버킷. 빈 버킷은 0이고, 참여 전 평가(04:59)와 정상 평가(05:02:40)는 이상 평가로 세지 않는다.
		admin(get("/api/v1/admin/concerts/c-1/trends").param("serviceId", SERVICE)
				.param("from", "2026-11-24T04:58:00Z").param("to", "2026-11-24T05:04:00Z"))
				.andExpect(jsonPath("$.data.intervalSeconds").value(60))
				.andExpect(jsonPath("$.data.from").value("2026-11-24T04:58:00Z"))
				.andExpect(jsonPath("$.data.sanctionRecorded").value(false))
				.andExpect(jsonPath("$.data.buckets", hasSize(6)))
				.andExpect(jsonPath("$.data.buckets[1].startsAt").value("2026-11-24T04:59:00Z"))
				.andExpect(jsonPath("$.data.buckets[1].anomalyAssessmentCount").value(0))
				.andExpect(jsonPath("$.data.buckets[2].attemptCount").value(2))  // A, B
				.andExpect(jsonPath("$.data.buckets[3].attemptCount").value(1))  // C
				.andExpect(jsonPath("$.data.buckets[4].attemptCount").value(0))
				.andExpect(jsonPath("$.data.buckets[4].anomalyAssessmentCount").value(1))
				.andExpect(jsonPath("$.data.buckets[5].attemptCount").value(1))  // D
				.andExpect(jsonPath("$.data.buckets[5].sanctionAppliedCount").value(0));
		admin(get("/api/v1/admin/concerts/c-1/trends").param("serviceId", SERVICE)
				.param("from", "2026-11-24T05:00:00Z").param("to", "2026-11-24T05:10:00Z").param("intervalSeconds", "300"))
				.andExpect(jsonPath("$.data.buckets", hasSize(2)))
				.andExpect(jsonPath("$.data.buckets[0].attemptCount").value(4));
		for (var invalid : List.of(
				Map.of("intervalSeconds", "10"),                                                        // 60초 미만
				Map.of("from", "2026-11-24T05:00:00Z", "to", "2026-11-24T04:00:00Z"),                   // 뒤집힌 범위
				Map.of("from", "2026-11-23T00:00:00Z", "to", "2026-11-24T05:00:00Z"),                   // 버킷 500개 초과
				Map.of("from", "2026-11-24T05:00:00", "to", "2026-11-24T05:10:00Z"))) {                 // 오프셋 없음
			var request = get("/api/v1/admin/concerts/c-1/trends").param("serviceId", SERVICE).header("X-API-Key", ADMIN);
			invalid.forEach(request::param);
			mvc.perform(request).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/v1/admin/concerts/c-1/trends").param("serviceId", SERVICE).header("X-API-Key", OTHER_ADMIN))
				.andExpect(status().isForbidden());
		// 조회만으로는 Credit 이력이나 정책 결정이 생기지 않는다.
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM credit_history h JOIN user_account u ON u.user_id=h.user_id "
				+ "WHERE u.service_id=?", Integer.class, SERVICE));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM policy_decision d JOIN user_account u ON u.user_id=d.user_id "
				+ "WHERE u.service_id=?", Integer.class, SERVICE));
	}

	// ---------------------------------------------------------------- helpers

	private String newUser(String service) {
		return userService.register(new UserRegistrationRequest(service, UUID.randomUUID().toString()), service)
				.user().userId();
	}

	private ResultActions admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
			throws Exception {
		return mvc.perform(request.header("X-API-Key", ADMIN)).andExpect(status().isOk());
	}

	private ResultActions service(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			String key) throws Exception {
		return mvc.perform(request.header("X-API-Key", key));
	}

	private long assess(String user, double score) {
		return riskAssessmentRepository.saveAndFlush(
				new RiskAssessment(userAccountRepository.findById(user).orElseThrow(), score, MODEL)).getAssessmentId();
	}

	private void concert(String concertId, String name) {
		jdbc.update("INSERT INTO concert(service_id,concert_id,name,starts_at,ends_at,venue) VALUES (?,?,?,?,?,?)",
				SERVICE, concertId, name, "2026-12-01 10:00:00", "2026-12-01 12:00:00", "Hall");
	}

	/** 예매 시도의 단계 이벤트 1건. 서비스의 행동 이벤트와 같은 metadata 형식이다. */
	private void attempt(String user, String concertId, String attemptId, String stage, String status, int sequence,
			String occurredAtUtc) throws Exception {
		var metadata = new LinkedHashMap<String, Object>();
		metadata.put("attemptId", attemptId);
		metadata.put("concertId", concertId);
		metadata.put("stage", stage);
		metadata.put("status", status);
		metadata.put("sequence", sequence);
		rawEvent(user, "BOOKING_ATTEMPT", null, occurredAtUtc, mapper.writeValueAsString(metadata));
	}

	private void rawEvent(String user, String type, String bookingId, String occurredAtUtc, String metadata) {
		jdbc.update("INSERT INTO behavior_event(user_id,service_id,external_event_id,event_type,booking_id,occurred_at,metadata) "
				+ "VALUES (?,?,?,?,?,?,?)", user, SERVICE, UUID.randomUUID().toString(), type, bookingId, occurredAtUtc, metadata);
	}

	private Map<String, Object> performance(List<String> ticketIds) {
		var metadata = new LinkedHashMap<String, Object>();
		metadata.put("concertId", "c-1");
		metadata.put("performanceStartsAt", "2026-10-10T10:00:00Z");
		metadata.put("performanceEndsAt", "2026-10-10T12:00:00Z");
		if (ticketIds != null) metadata.put("ticketIds", ticketIds);
		return metadata;
	}

	/** 실제 이벤트 API로 BOOKING · CHECK_IN을 보낸다(관람 이력과 Credit 자동 갱신 경로). */
	private void event(String type, String bookingId, String ticketId, String occurredAt, Map<String, Object> metadata)
			throws Exception {
		var body = new LinkedHashMap<String, Object>();
		body.put("serviceId", SERVICE);
		body.put("externalEventId", UUID.randomUUID().toString());
		body.put("userId", userId);
		body.put("eventType", type);
		body.put("bookingId", bookingId);
		body.put("ticketId", ticketId);
		body.put("occurredAt", occurredAt);
		body.put("metadata", metadata);
		mvc.perform(post("/api/v1/events").header("X-API-Key", KEY).contentType("application/json")
				.content(mapper.writeValueAsString(body))).andExpect(status().isCreated());
	}

	private long decide(String user, String body, String expectedConcert) throws Exception {
		JsonNode data = mapper.readTree(mvc.perform(post("/api/v1/users/" + user + "/policy-decisions")
				.header("X-API-Key", KEY).contentType("application/json").content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.action").value("REQUIRE_CAPTCHA"))
				.andExpect(jsonPath("$.data.concertId").value(expectedConcert))
				.andExpect(jsonPath("$.data.decidedAt").value(endsWith("Z")))
				.andReturn().getResponse().getContentAsString()).get("data");
		return data.get("decisionId").asLong();
	}

	private String application(String key, String service, String user, long decisionId, String concertId,
			String action, String result, String appliedAt) throws Exception {
		var body = new LinkedHashMap<String, Object>();
		body.put("serviceId", service);
		body.put("externalApplicationId", key);
		body.put("userId", user);
		body.put("decisionId", decisionId);
		body.put("concertId", concertId);
		body.put("action", action);
		body.put("result", result);
		body.put("appliedAt", appliedAt);
		return mapper.writeValueAsString(body);
	}

	private ResultActions apply(String key, String user, long decisionId, String concertId, String action, String result)
			throws Exception {
		return mvc.perform(post("/api/v1/policy-applications").header("X-API-Key", KEY).contentType("application/json")
				.content(application(key, SERVICE, user, decisionId, concertId, action, result, "2026-11-24T13:05:00.123+09:00")));
	}

	/**
	 * 스케줄러가 하는 일을 지정한 시각으로 한 번 실행한다. 이 테스트의 사용자만 평가 대상으로 남기고,
	 * AI 응답은 사용자별로 "OK:점수", "INSUFFICIENT_DATA", "INVALID" 중 하나를 흉내 낸다.
	 */
	private void runAi(String now, Map<String, String> outcomes) {
		RiskAssessmentCursorRepository ownOnly = mock(RiskAssessmentCursorRepository.class, delegatesTo(cursorRepository));
		doAnswer(invocation -> cursorRepository.findCandidates(invocation.<java.time.LocalDateTime>getArgument(0),
				Integer.MAX_VALUE).stream().filter(candidate -> outcomes.containsKey(candidate.getUserId())).toList())
				.when(ownOnly).findCandidates(any(), anyInt());
		AiInferenceClient ai = mock(AiInferenceClient.class);
		when(ai.predictBatch(anyList())).thenAnswer(invocation -> {
			List<AiPredictRequest> requests = invocation.getArgument(0);
			return requests.stream().map(request -> {
				String outcome = outcomes.get(request.userId());
				if (outcome.startsWith("OK:")) {
					double score = Double.parseDouble(outcome.substring(3));
					return new AiPrediction(request.userId(), "OK", 0.6, score, score >= 0.5, MODEL, now, null);
				}
				return new AiPrediction(request.userId(), outcome, null, null, null, MODEL, now,
						"INVALID".equals(outcome) ? List.of("features.bookingCount는 null일 수 없습니다.") : null);
			}).toList();
		});
		new RiskAssessmentService(ownOnly, featureAggregationService, ai, userAccountRepository,
				riskAssessmentRepository, riskAssessmentAttemptRepository, eventPublisher, transactionManager,
				Clock.fixed(Instant.parse(now), ZoneOffset.UTC), 100, 500).runOnce();
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, userId);
	}

	private int score() {
		return jdbc.queryForObject("SELECT credit_score FROM user_credit WHERE user_id=?", Integer.class, userId);
	}

	private Object latestCalculationId() {
		return jdbc.queryForObject("SELECT latest_calculation_id FROM user_credit WHERE user_id=?", Object.class, userId);
	}
}
