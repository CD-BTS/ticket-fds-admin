package com.bts.fairticket.credit;

import java.time.Clock;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import com.bts.fairticket.concert.repository.ConcertRepository;
import com.bts.fairticket.credit.dto.CreditEvaluationContext;
import com.bts.fairticket.credit.repository.BookingHistoryRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.credit.service.CreditEvaluationService;
import com.bts.fairticket.credit.service.NoShowService;
import com.bts.fairticket.user.ApiKeyTestSupport;
import com.bts.fairticket.user.dto.UserRegistrationRequest;
import com.bts.fairticket.user.repository.UserAccountRepository;
import com.bts.fairticket.user.service.UserService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #56: 이벤트 → 관람 이력 → Credit 흐름을 실제 이벤트 API와 MySQL로 검증한다.
 * 실행할 때마다 만든 임의 서비스의 데이터만 만들고 지운다.
 */
@Tag("mysql")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureMockMvc
class BookingHistoryMySqlTests {
	private static final String SERVICE = "bh-" + UUID.randomUUID();
	private static final String OTHER = "bh-" + UUID.randomUUID();
	private static final String KEY = ApiKeyTestSupport.randomKey();
	private static final String OTHER_KEY = ApiKeyTestSupport.randomKey();
	private static final String ADMIN = ApiKeyTestSupport.randomKey();
	private static final String OTHER_ADMIN = ApiKeyTestSupport.randomKey();

	private static final String CONCERT = "concert-1";
	private static final String STARTS = "2026-10-10T10:00:00Z";
	private static final String ENDS = "2026-10-10T12:00:00Z";
	private static final Duration GRACE = Duration.ofHours(1);

	@DynamicPropertySource
	static void keys(DynamicPropertyRegistry registry) {
		registry.add("BTS_API_KEY_HASHES", () ->
				ApiKeyTestSupport.configuration(Map.of(SERVICE, KEY, OTHER, OTHER_KEY)));
		registry.add("BTS_ADMIN_API_KEYS", () -> "[{\"hash\":\"" + ApiKeyTestSupport.hash(ADMIN)
				+ "\",\"services\":[\"" + SERVICE + "\"],\"allServices\":false},{\"hash\":\""
				+ ApiKeyTestSupport.hash(OTHER_ADMIN) + "\",\"services\":[\"" + OTHER + "\"],\"allServices\":false}]");
	}

	@Autowired private MockMvc mvc;
	@Autowired private JsonMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private UserService userService;
	@Autowired private CreditEvaluationService creditEvaluationService;
	@Autowired private BookingHistoryRepository bookingHistoryRepository;
	@Autowired private UserCreditRepository userCreditRepository;
	@Autowired private UserAccountRepository userAccountRepository;
	@Autowired private ConcertRepository concertRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	private String userId;
	private String bookingId;
	private String ticketA;
	private String ticketB;

	@BeforeEach
	void createOwnUser() {
		userId = newUser();
		bookingId = "b_" + UUID.randomUUID();
		ticketA = "t_" + UUID.randomUUID();
		ticketB = "t_" + UUID.randomUUID();
	}

	@AfterEach
	void cleanOnlyOwnRandomServiceFixtures() {
		String ownUsers = "(SELECT user_id FROM user_account WHERE service_id IN (?, ?))";
		for (String table : List.of("credit_history", "booking_history", "user_card", "behavior_event", "user_credit")) {
			jdbc.update("DELETE FROM " + table + " WHERE user_id IN " + ownUsers, SERVICE, OTHER);
		}
		jdbc.update("DELETE FROM user_account WHERE service_id IN (?, ?)", SERVICE, OTHER);
		jdbc.update("DELETE FROM concert WHERE service_id IN (?, ?)", SERVICE, OTHER);
	}

	// ---------------------------------------------------------------- 예매·검표

	@Test
	void bookingCreatesOneBookedRowPerTicketWithoutChangingCredit() throws Exception {
		booking(201);

		assertEquals("BOOKED", ticketStatus(ticketA));
		assertEquals("BOOKED", ticketStatus(ticketB));
		var row = jdbc.queryForMap("SELECT * FROM booking_history WHERE ticket_id=?", ticketA);
		assertEquals(SERVICE, row.get("service_id"));
		assertEquals(bookingId, row.get("booking_reference"));
		assertEquals(CONCERT, row.get("event_id"));
		assertEquals("2026-10-01 09:00:00", time(ticketA, "booked_at"));
		assertEquals("2026-10-10 10:00:00", time(ticketA, "event_at"));
		assertEquals("2026-10-10 12:00:00", time(ticketA, "event_ends_at"));
		// 예매만으로는 A, N, C가 바뀌지 않으므로 Credit과 credit_history는 그대로다.
		assertEquals(50, score());
		assertEquals(0, creditHistoryCount());
	}

	@Test
	void bookingWithoutTicketIdsIsStillCollectedButCreatesNoHistory() throws Exception {
		event("BOOKING", UUID.randomUUID().toString(), bookingId, null, "2026-10-01T09:00:00Z",
				Map.of("bookingDurationSeconds", 12.5), 201);

		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM behavior_event WHERE user_id=?", Integer.class, userId));
		assertEquals(0, historyCount());
	}

	@Test
	void checkInCompletesOnlyThatTicketAndUpdatesCreditOnce() throws Exception {
		booking(201);
		long eventId = checkIn(ticketA, UUID.randomUUID().toString(), 201);

		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals("BOOKED", ticketStatus(ticketB));
		// BaseCredit = 100 * (1 + 5) / (1 + 10) = 54.5 → 55, 감점 없음
		assertEquals(55, score());
		assertEquals(0.0, penalty());
		var history = jdbc.queryForMap("SELECT * FROM credit_history WHERE user_id=?", userId);
		assertEquals(eventId, ((Number) history.get("event_id")).longValue());
		assertEquals(50, history.get("score_before"));
		assertEquals(55, history.get("score_after"));
	}

	@Test
	void duplicateCheckInsDoNotDuplicateHistoryOrCreditRecords() throws Exception {
		booking(201);
		String external = UUID.randomUUID().toString();
		checkIn(ticketA, external, 201);
		// 같은 이벤트 재전송: 200으로 기존 이벤트를 돌려주고 아무것도 바꾸지 않는다.
		checkIn(ticketA, external, 200);
		// 이벤트 ID만 다른 같은 티켓의 CHECK_IN: 이벤트는 저장되지만 이력과 Credit은 그대로다.
		checkIn(ticketA, UUID.randomUUID().toString(), 201);
		// BOOKING 재전송도 이력을 늘리지 않는다.
		booking(200);

		assertEquals(2, historyCount());
		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals(55, score());
		assertEquals(1, creditHistoryCount());
	}

	@Test
	void checkInBeforeBookingIsKeptWhenBookingAndCancelArriveLater() throws Exception {
		checkIn(ticketA, UUID.randomUUID().toString(), 201);
		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals(55, score());

		booking(201);
		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals("BOOKED", ticketStatus(ticketB));
		// 뒤늦은 BOOKING은 상태를 되돌리지 않고 예매 정보만 채운다.
		assertEquals("2026-10-01 09:00:00", time(ticketA, "booked_at"));
		assertEquals(2, historyCount());

		cancel(201);
		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals("CANCELLED", ticketStatus(ticketB));
		assertEquals(55, score());
		assertEquals(1, creditHistoryCount());
	}

	// ---------------------------------------------------------------- NO_SHOW

	@Test
	void unattendedTicketBecomesNoShowOnlyAfterEndPlusGrace() throws Exception {
		booking(201);
		checkIn(ticketA, UUID.randomUUID().toString(), 201);

		// 공연은 끝났지만 유예 시간(1시간)이 지나지 않았다.
		assertEquals(0, noShowsAt("2026-10-10T12:59:59Z"));
		assertEquals("BOOKED", ticketStatus(ticketB));

		assertEquals(1, noShowsAt("2026-10-10T13:00:00Z"));
		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals("NO_SHOW", ticketStatus(ticketB));
		// A=1, N=1: 100 * 6 / (1 + 2 + 10) = 46.2 → 46
		assertEquals(46, score());
		assertEquals(2, creditHistoryCount());
		assertNull(jdbc.queryForObject("SELECT event_id FROM credit_history WHERE user_id=? AND score_after=46",
				Long.class, userId));

		// 다시 실행해도 같은 티켓을 두 번 판정하지 않는다.
		assertEquals(0, noShowsAt("2026-10-11T00:00:00Z"));
		assertEquals(46, score());
		assertEquals(2, creditHistoryCount());
	}

	@Test
	void severalNoShowTicketsOfOneUserAreRecordedAsOneCreditChange() throws Exception {
		booking(201);

		assertEquals(2, noShowsAt("2026-10-10T13:00:00Z"));
		// N=2: 100 * 5 / (4 + 10) = 35.7 → 36
		assertEquals(36, score());
		assertEquals(1, creditHistoryCount());
	}

	@Test
	void cancelledTicketsAreNeverMarkedNoShow() throws Exception {
		booking(201);
		cancel(201);
		assertEquals("CANCELLED", ticketStatus(ticketA));
		assertEquals("CANCELLED", ticketStatus(ticketB));

		assertEquals(0, noShowsAt("2026-10-11T00:00:00Z"));
		assertEquals("CANCELLED", ticketStatus(ticketA));
		assertEquals(50, score());
		assertEquals(0, creditHistoryCount());
	}

	@Test
	void lateCheckInCorrectsNoShowAndRestoresCredit() throws Exception {
		booking(201);
		assertEquals(2, noShowsAt("2026-10-10T13:00:00Z"));
		assertEquals(36, score());

		long eventId = checkIn(ticketA, UUID.randomUUID().toString(), 201);

		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals("NO_SHOW", ticketStatus(ticketB));
		// A=1, N=1 → 46. 처음부터 관람으로 처리됐을 때와 같은 값이다.
		assertEquals(46, score());
		assertEquals(2, creditHistoryCount());
		assertEquals(36, jdbc.queryForObject("SELECT score_before FROM credit_history WHERE event_id=?",
				Integer.class, eventId));
	}

	@Test
	void lateCancelCorrectsNoShow() throws Exception {
		booking(201);
		assertEquals(2, noShowsAt("2026-10-10T13:00:00Z"));

		cancel(201);

		assertEquals("CANCELLED", ticketStatus(ticketA));
		assertEquals("CANCELLED", ticketStatus(ticketB));
		assertEquals(50, score());
		assertEquals(2, creditHistoryCount());
	}

	@Test
	void postponedConcertDelaysNoShowUntilTheNewEndTime() throws Exception {
		booking(201);
		// 예매 이후 공연이 하루 뒤로 미뤄졌다.
		jdbc.update("INSERT INTO concert(service_id,concert_id,name,starts_at,ends_at,venue) VALUES (?,?,?,?,?,?)",
				SERVICE, CONCERT, "연기된 공연", "2026-10-11 10:00:00", "2026-10-11 12:00:00", "홀");

		assertEquals(0, noShowsAt("2026-10-10T13:00:00Z"));
		assertEquals("BOOKED", ticketStatus(ticketA));
		assertEquals("2026-10-11 12:00:00", time(ticketA, "event_ends_at"));

		assertEquals(2, noShowsAt("2026-10-11T13:00:00Z"));
	}

	// ---------------------------------------------------------------- Credit 감점 유지·일관성

	@Test
	void storedPenaltyIsKeptAndAutomaticResultEqualsManualEvaluation() throws Exception {
		// 수동 평가: IP 집중도 5 + 요청 간격 분산 5 = DynamicPenalty 10 → 50 - 10 = 40
		var context = new CreditEvaluationContext("정기 평가", 5, 0.5);
		assertEquals(40, creditEvaluationService.evaluateAndApplyCredit(userId, context).newScore());
		assertEquals(10.0, penalty());

		booking(201);
		checkIn(ticketA, UUID.randomUUID().toString(), 201);

		// 자동 갱신: 54.5 - 10 = 44.5 → 45. 감점이 사라졌다면 55가 된다.
		assertEquals(45, score());
		assertEquals(10.0, penalty());
		// 같은 관람 이력, 같은 감점 조건의 수동 평가와 결과가 같다.
		var manual = creditEvaluationService.evaluateAndApplyCredit(userId, context);
		assertEquals(45, manual.oldScore());
		assertEquals(45, manual.newScore());
	}

	@Test
	void creditEvaluationWaitsForInProgressAttendanceChangeAndUsesTheLatestHistory() throws Exception {
		booking(201);
		var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			var pending = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
				// 진행 중인 CHECK_IN과 같은 순서로 잠근다: user_account → user_credit.
				userAccountRepository.findByIdForUpdate(userId).orElseThrow();
				userCreditRepository.findByIdForUpdate(userId).orElseThrow();
				// 그 사이 시작된 Credit 평가(AI 평가 반영 · 수동 평가 공통)는 잠금을 기다린다.
				var evaluation = executor.submit(() -> creditEvaluationService.evaluateAndApplyCredit(userId,
						CreditEvaluationContext.defaultContext()));
				org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
						() -> evaluation.get(1, java.util.concurrent.TimeUnit.SECONDS));
				// 관람 완료가 반영되고 커밋된다.
				jdbc.update("UPDATE booking_history SET status='COMPLETED' WHERE service_id=? AND ticket_id=?", SERVICE, ticketA);
				return evaluation;
			});
			// #63: 잠금이 풀린 뒤의 평가는 방금 커밋된 관람 이력(A=1)으로 계산한다. 이전 통계(A=0 → 50)로 덮어쓰지 않는다.
			assertEquals(55, pending.get(10, java.util.concurrent.TimeUnit.SECONDS).newScore());
			assertEquals(55, score());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void scoreIsRestoredAfterBeingClampedAtZero() throws Exception {
		// 저장된 감점이 최대(70)인 사용자: 노쇼가 쌓이면 0에서 잘린다.
		jdbc.update("UPDATE user_credit SET credit_score=0, dynamic_penalty=70 WHERE user_id=?", userId);
		booking(201);
		assertEquals(2, noShowsAt("2026-10-10T13:00:00Z"));
		assertEquals(0, score());

		// 감점이 해소된 수동 평가 뒤에는 관람 이력만으로 계산된 값으로 돌아온다.
		assertEquals(36, creditEvaluationService.evaluateAndApplyCredit(userId,
				CreditEvaluationContext.defaultContext()).newScore());
		checkIn(ticketA, UUID.randomUUID().toString(), 201);
		assertEquals(46, score());
	}

	@Test
	void legacyUserWithoutAnyEvaluationIsRecalculatedWithZeroPenalty() throws Exception {
		// #56 이전에 등록된 사용자: 점수 70, 저장된 감점 없음, 평가 이력 없음
		jdbc.update("UPDATE user_credit SET credit_score=70, dynamic_penalty=NULL WHERE user_id=?", userId);
		booking(201);
		assertEquals(70, score());

		checkIn(ticketA, UUID.randomUUID().toString(), 201);

		assertEquals(55, score());
		assertEquals(0.0, penalty());
		assertEquals(70, jdbc.queryForObject("SELECT score_before FROM credit_history WHERE user_id=?",
				Integer.class, userId));
	}

	@Test
	void legacyUserEvaluatedWithUnknownPenaltyKeepsScoreUntilNextEvaluation() throws Exception {
		// #56 이전에 수동 평가를 받은 사용자: 당시 감점을 알 수 없다.
		jdbc.update("UPDATE user_credit SET credit_score=35, dynamic_penalty=NULL WHERE user_id=?", userId);
		jdbc.update("INSERT INTO credit_history(user_id,score_before,score_after,reason) VALUES (?,70,35,'이전 평가')", userId);

		booking(201);
		checkIn(ticketA, UUID.randomUUID().toString(), 201);

		// 관람 이력은 반영되지만 점수는 역산하지 않고 그대로 둔다.
		assertEquals("COMPLETED", ticketStatus(ticketA));
		assertEquals(35, score());
		assertNull(penalty());
		assertEquals(1, creditHistoryCount());

		// 다음 수동 평가가 감점을 저장하면서 그동안의 관람 이력까지 반영한다.
		assertEquals(55, creditEvaluationService.evaluateAndApplyCredit(userId,
				CreditEvaluationContext.defaultContext()).newScore());
		assertEquals(0.0, penalty());
	}

	// ---------------------------------------------------------------- 조회 API

	@Test
	void serviceAndAdminCanListTicketHistoriesWithConcertInfo() throws Exception {
		jdbc.update("INSERT INTO concert(service_id,concert_id,name,starts_at,ends_at,venue) VALUES (?,?,?,?,?,?)",
				SERVICE, CONCERT, "테스트 공연", "2026-10-10 10:00:00", "2026-10-10 12:00:00", "테스트 홀");
		booking(201);
		checkIn(ticketA, UUID.randomUUID().toString(), 201);

		for (var request : List.of(
				get("/api/v1/users/" + userId + "/bookings").header("X-API-Key", KEY),
				get("/api/v1/admin/users/" + userId + "/bookings").header("X-API-Key", ADMIN))) {
			mvc.perform(request.param("sort", "historyId,asc"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.totalElements").value(2))
					.andExpect(jsonPath("$.data.content[0].serviceId").value(SERVICE))
					.andExpect(jsonPath("$.data.content[0].bookingId").value(bookingId))
					.andExpect(jsonPath("$.data.content[0].ticketId").value(ticketA))
					.andExpect(jsonPath("$.data.content[0].concertId").value(CONCERT))
					.andExpect(jsonPath("$.data.content[0].concertName").value("테스트 공연"))
					.andExpect(jsonPath("$.data.content[0].venue").value("테스트 홀"))
					.andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"))
					.andExpect(jsonPath("$.data.content[0].bookedAt").value("2026-10-01T09:00:00Z"))
					.andExpect(jsonPath("$.data.content[0].performanceStartsAt").value(STARTS))
					.andExpect(jsonPath("$.data.content[0].performanceEndsAt").value(ENDS))
					.andExpect(jsonPath("$.data.content[1].ticketId").value(ticketB))
					.andExpect(jsonPath("$.data.content[1].status").value("BOOKED"));
		}

		mvc.perform(get("/api/v1/users/" + userId + "/bookings").header("X-API-Key", KEY).param("status", "BOOKED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].ticketId").value(ticketB));
		mvc.perform(get("/api/v1/users/" + userId + "/bookings").header("X-API-Key", KEY).param("status", "UNKNOWN"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void historiesAreHiddenFromOtherServicesAndUnauthenticatedCallers() throws Exception {
		booking(201);

		mvc.perform(get("/api/v1/users/" + userId + "/bookings"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/users/" + userId + "/bookings").header("X-API-Key", OTHER_KEY))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/admin/users/" + userId + "/bookings").header("X-API-Key", OTHER_ADMIN))
				.andExpect(status().isNotFound());
		// 서비스 키로 관리자 경로를, 관리자 키로 서비스 경로를 호출할 수 없다.
		mvc.perform(get("/api/v1/admin/users/" + userId + "/bookings").header("X-API-Key", KEY))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/users/" + userId + "/bookings").header("X-API-Key", ADMIN))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- helpers

	private String newUser() {
		return userService.register(new UserRegistrationRequest(SERVICE, UUID.randomUUID().toString()), SERVICE)
				.user().userId();
	}

	private Map<String, Object> performance() {
		var metadata = new LinkedHashMap<String, Object>();
		metadata.put("concertId", CONCERT);
		metadata.put("performanceStartsAt", STARTS);
		metadata.put("performanceEndsAt", ENDS);
		return metadata;
	}

	// Demo Ticketing이 보내는 형식과 같은 이벤트를 만든다. 이벤트 ID는 예매·티켓에 고정이라 다시 부르면 재전송이 된다.
	private void booking(int expectedStatus) throws Exception {
		var metadata = performance();
		metadata.put("ticketIds", List.of(ticketA, ticketB));
		metadata.put("bookingDurationSeconds", 12.5);
		event("BOOKING", bookingId + ":BOOKING", bookingId, null, "2026-10-01T09:00:00.123Z", metadata, expectedStatus);
	}

	private void cancel(int expectedStatus) throws Exception {
		var metadata = performance();
		metadata.put("ticketIds", List.of(ticketA, ticketB));
		event("CANCEL", bookingId + ":CANCEL", bookingId, null, "2026-10-02T09:00:00.456Z", metadata, expectedStatus);
	}

	private long checkIn(String ticketId, String externalEventId, int expectedStatus) throws Exception {
		var metadata = performance();
		metadata.put("deviceChanged", false);
		return event("CHECK_IN", externalEventId, bookingId, ticketId, "2026-10-10T09:45:30.789Z", metadata, expectedStatus);
	}

	private long event(String type, String externalEventId, String booking, String ticket, String occurredAt,
			Map<String, Object> metadata, int expectedStatus) throws Exception {
		var body = new LinkedHashMap<String, Object>();
		body.put("serviceId", SERVICE);
		body.put("externalEventId", externalEventId);
		body.put("userId", userId);
		body.put("eventType", type);
		body.put("bookingId", booking);
		body.put("ticketId", ticket);
		body.put("occurredAt", occurredAt);
		body.put("metadata", metadata);
		String response = mvc.perform(post("/api/v1/events").header("X-API-Key", KEY)
				.contentType("application/json").content(mapper.writeValueAsString(body)))
				.andExpect(status().is(expectedStatus))
				.andReturn().getResponse().getContentAsString();
		return mapper.readTree(response).get("data").get("eventId").asLong();
	}

	// 스케줄러가 하는 일을 지정한 시각으로 한 번 실행한다. 유예 시간은 기본값과 같은 1시간이다.
	private int noShowsAt(String now) {
		return new NoShowService(bookingHistoryRepository, userCreditRepository, userAccountRepository,
				concertRepository, creditEvaluationService, transactionManager,
				Clock.fixed(Instant.parse(now), ZoneOffset.UTC), GRACE).markNoShows();
	}

	private String ticketStatus(String ticketId) {
		return jdbc.queryForObject("SELECT status FROM booking_history WHERE service_id=? AND ticket_id=?",
				String.class, SERVICE, ticketId);
	}

	private String time(String ticketId, String column) {
		return jdbc.queryForObject("SELECT DATE_FORMAT(" + column + ", '%Y-%m-%d %H:%i:%s') FROM booking_history "
				+ "WHERE service_id=? AND ticket_id=?", String.class, SERVICE, ticketId);
	}

	private int historyCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM booking_history WHERE user_id=?", Integer.class, userId);
	}

	private int score() {
		return jdbc.queryForObject("SELECT credit_score FROM user_credit WHERE user_id=?", Integer.class, userId);
	}

	private Double penalty() {
		return jdbc.queryForObject("SELECT dynamic_penalty FROM user_credit WHERE user_id=?", Double.class, userId);
	}

	private int creditHistoryCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM credit_history WHERE user_id=?", Integer.class, userId);
	}
}
