package com.bts.fairticket.admin.repository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bts.fairticket.admin.dto.RiskStatistics;
import com.bts.fairticket.admin.dto.SanctionStatistics;

/**
 * 관리자 통계용 조회 (#67). 읽기만 하며 Credit, 평가, 정책 결정을 만들거나 바꾸지 않는다.
 * 서비스 식별자와 공연 ID는 대소문자를 구분해야 하므로 일반 비교와 함께 hex 비교를 건다(기존 조회와 같은 방식).
 * 시각 파라미터는 문자열로 넘긴다. LocalDateTime을 그대로 바인딩하면 JVM 시간대로 변환되어 UTC 값이 어긋난다(#63).
 */
@Repository
public class AdminStatisticsRepository {

	private static final DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

	/** 사용자의 최신 성공 평가 1건. Credit 산출 · 정책 판정과 같은 순서(assessed_at, assessment_id)다. */
	private static final String LATEST_ASSESSMENT = """
			left join risk_assessment a on a.user_id = u.user_id and a.assessment_id = (
				select x.assessment_id from risk_assessment x where x.user_id = u.user_id
				order by x.assessed_at desc, x.assessment_id desc limit 1)
			""";

	/** 공연 참여자: 그 공연의 예매 시도를 시작(STARTED/ENTERED)한 사용자. 기존 공연 통계 · 예매자 목록과 같은 기준이다. */
	private static final String CONCERT_ATTEMPT_EVENTS = """
			from behavior_event e join user_account u on u.user_id = e.user_id
			where e.service_id = :serviceId and hex(e.service_id) = hex(:serviceId)
			  and hex(u.service_id) = hex(:serviceId) and e.event_type = 'BOOKING_ATTEMPT'
			  and hex(JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.concertId'))) = hex(:concertId)
			""";
	private static final String STARTED = """
			  and JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.stage')) = 'STARTED'
			  and JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.status')) = 'ENTERED'
			""";
	private static final String CONCERT_SCOPE = "service_id = :serviceId and hex(service_id) = hex(:serviceId)"
			+ " and concert_id = :concertId and hex(concert_id) = hex(:concertId)";

	private final NamedParameterJdbcTemplate jdbc;

	public AdminStatisticsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

	// ------------------------------------------------------------------ 사용자 집합 통계

	/** @param services null이면 전체 서비스(전역 관리자 키), 빈 집합이면 결과 없음 */
	public RiskStatistics serviceRisk(Set<String> services) {
		if (services != null && services.isEmpty()) return RiskStatistics.from(List.of());
		var params = new HashMap<String, Object>();
		return RiskStatistics.from(buckets("""
				select c.credit_score, case when a.assessment_id is null then null else floor(a.anomaly_score * 100) end as score_bucket,
					count(*) as users
				from user_account u join user_credit c on c.user_id = u.user_id
				""" + LATEST_ASSESSMENT + " where " + serviceScope("u.service_id", services, params)
				+ " group by c.credit_score, score_bucket", params));
	}

	public RiskStatistics concertRisk(String serviceId, String concertId) {
		return RiskStatistics.from(buckets("""
				select c.credit_score, case when a.assessment_id is null then null else floor(a.anomaly_score * 100) end as score_bucket,
					count(*) as users
				from (select distinct e.user_id
				""" + CONCERT_ATTEMPT_EVENTS + STARTED + """
				) p join user_account u on u.user_id = p.user_id join user_credit c on c.user_id = u.user_id
				""" + LATEST_ASSESSMENT + " group by c.credit_score, score_bucket", concert(serviceId, concertId)));
	}

	private List<RiskStatistics.Bucket> buckets(String sql, Map<String, Object> params) {
		return jdbc.query(sql, params, (rs, row) -> {
			int bucket = rs.getInt("score_bucket");
			Integer scoreBucket = rs.wasNull() ? null : bucket; // 미평가 사용자는 점수 구간이 없다(0이 아니다)
			return new RiskStatistics.Bucket(rs.getInt("credit_score"), scoreBucket, rs.getLong("users"));
		});
	}

	/** 가장 최근 평가 시도가 INSUFFICIENT_DATA인 고유 사용자 수. */
	public long insufficientDataUsers(Set<String> services) {
		if (services != null && services.isEmpty()) return 0;
		var params = new HashMap<String, Object>();
		Long count = jdbc.queryForObject("""
				select count(*) from user_account u join risk_assessment_attempt t on t.user_id = u.user_id
				where t.status = 'INSUFFICIENT_DATA'
				  and t.attempt_id = (select max(x.attempt_id) from risk_assessment_attempt x where x.user_id = u.user_id)
				  and """ + " " + serviceScope("u.service_id", services, params), params, Long.class);
		return count == null ? 0 : count;
	}

	// ------------------------------------------------------------------ 실제 정책 적용 통계

	public SanctionStatistics serviceSanctions(Set<String> services) {
		if (services != null && services.isEmpty()) return SanctionStatistics.notRecorded();
		var params = new HashMap<String, Object>();
		String scope = serviceScope("service_id", services, params);
		return sanctions(scope, scope, params);
	}

	public SanctionStatistics concertSanctions(String serviceId, String concertId) {
		// recorded는 공연이 아니라 서비스 기준이다: 서비스가 기록을 보내고 있는데 이 공연에 없으면 실제로 0건이다.
		return sanctions("service_id = :serviceId and hex(service_id) = hex(:serviceId)", CONCERT_SCOPE,
				concert(serviceId, concertId));
	}

	private SanctionStatistics sanctions(String recordedScope, String scope, Map<String, Object> params) {
		Boolean recorded = jdbc.queryForObject(
				"select exists(select 1 from policy_application where " + recordedScope + ")", params, Boolean.class);
		if (!Boolean.TRUE.equals(recorded)) return SanctionStatistics.notRecorded();

		Long users = jdbc.queryForObject("select count(distinct user_id) from policy_application where " + scope,
				params, Long.class);
		Map<String, SanctionStatistics.PolicyCount> byPolicy = new LinkedHashMap<>();
		for (String action : List.of("REQUIRE_CAPTCHA", "DELAY", "RESTRICT")) {
			byPolicy.put(action, new SanctionStatistics.PolicyCount(0, 0));
		}
		jdbc.query("select action, count(distinct user_id) as users, count(distinct decision_id) as applications "
				+ "from policy_application where " + scope + " group by action", params,
				(org.springframework.jdbc.core.RowCallbackHandler) rs -> byPolicy.put(rs.getString("action"),
						new SanctionStatistics.PolicyCount(rs.getLong("users"), rs.getLong("applications"))));
		long[] captcha = jdbc.queryForObject("""
				select count(distinct decision_id) as challenged,
					count(distinct case when result = 'PASSED' then decision_id end) as passed,
					count(distinct case when result = 'FAILED' then decision_id end) as failed
				from policy_application where action = 'REQUIRE_CAPTCHA' and """ + " " + scope, params,
				(rs, row) -> new long[]{rs.getLong("challenged"), rs.getLong("passed"), rs.getLong("failed")});
		return new SanctionStatistics(true, users == null ? 0 : users, byPolicy,
				new SanctionStatistics.CaptchaOutcome(captcha[0], captcha[1], captcha[2]));
	}

	// ------------------------------------------------------------------ 공연별 예매 통계

	/**
	 * 시도별 최종 단계 · 상태별 고유 시도 수. 모집단은 STARTED/ENTERED로 시작이 확인된 시도이고(공연 상세의
	 * bookingAttemptCount와 같다), 최종 상태는 AttemptQueryService와 같이 sequence가 가장 큰 이벤트로 정한다.
	 * @return "STAGE/STATUS" → 시도 수
	 */
	public Map<String, Long> attemptOutcomes(String serviceId, String concertId) {
		Map<String, Long> result = new LinkedHashMap<>();
		jdbc.query("""
				with events as (
					select e.event_id, e.user_id,
						JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.attemptId')) as attempt_id,
						JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.stage')) as stage,
						JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.status')) as status,
						CAST(JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.sequence')) AS UNSIGNED) as seq
				""" + CONCERT_ATTEMPT_EVENTS + """
				), started as (
					select user_id, attempt_id from events where stage = 'STARTED' and status = 'ENTERED'
					group by user_id, attempt_id
				), ranked as (
					select e.*, row_number() over (partition by user_id, attempt_id order by seq desc, event_id desc) as rn
					from events e
				)
				select r.stage, r.status, count(*) as attempts
				from started s join ranked r on r.user_id = s.user_id and r.attempt_id = s.attempt_id and r.rn = 1
				group by r.stage, r.status order by r.stage, r.status
				""", concert(serviceId, concertId),
				(org.springframework.jdbc.core.RowCallbackHandler) rs ->
						result.put(rs.getString("stage") + "/" + rs.getString("status"), rs.getLong("attempts")));
		return result;
	}

	/** 그 공연의 BOOKING 또는 CANCEL 이벤트가 가리키는 고유 예매(bookingId) 수. 재전송 이벤트는 저장되지 않으므로 세지 않는다. */
	public long distinctBookings(String serviceId, String concertId, String eventType) {
		var params = concert(serviceId, concertId);
		params.put("eventType", eventType);
		Long count = jdbc.queryForObject("""
				select count(distinct e.booking_id) from behavior_event e join user_account u on u.user_id = e.user_id
				where e.service_id = :serviceId and hex(e.service_id) = hex(:serviceId)
				  and hex(u.service_id) = hex(:serviceId) and e.event_type = :eventType
				  and hex(JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.concertId'))) = hex(:concertId)
				""", params, Long.class);
		return count == null ? 0 : count;
	}

	/** 입장 완료 티켓 수(booking_history의 COMPLETED). 티켓 1장당 1행이라 중복 검표는 세지 않는다. */
	public long checkedInTickets(String serviceId, String concertId) {
		Long count = jdbc.queryForObject("""
				select count(*) from booking_history
				where service_id = :serviceId and hex(service_id) = hex(:serviceId)
				  and event_id = :concertId and hex(event_id) = hex(:concertId) and status = 'COMPLETED'
				""", concert(serviceId, concertId), Long.class);
		return count == null ? 0 : count;
	}

	/** 공연 ID별 이상 사용자 수: 그 공연에 예매를 시도한 사용자 중 최신 이상 점수가 탐지 기준 이상인 고유 사용자. */
	public Map<String, Long> anomalyUsersByConcert(String serviceId, List<String> concertIds) {
		Map<String, Long> result = new HashMap<>();
		if (concertIds.isEmpty()) return result;
		var params = new HashMap<String, Object>(Map.of("serviceId", serviceId, "concertIds", concertIds,
				"threshold", RiskStatistics.ANOMALY_THRESHOLD));
		jdbc.query("""
				select p.concert_id, count(*) as users
				from (select distinct e.user_id, JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.concertId')) as concert_id
					from behavior_event e join user_account u on u.user_id = e.user_id
					where e.service_id = :serviceId and hex(e.service_id) = hex(:serviceId)
					  and hex(u.service_id) = hex(:serviceId) and e.event_type = 'BOOKING_ATTEMPT'
					  and JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.concertId')) in (:concertIds)
				""" + STARTED + """
				) p join user_account u on u.user_id = p.user_id
				""" + LATEST_ASSESSMENT + """
				where a.anomaly_score >= :threshold group by p.concert_id
				""", params, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
						result.merge(rs.getString("concert_id"), rs.getLong("users"), Long::sum));
		return result;
	}

	// ------------------------------------------------------------------ 시간대별 추이

	public record TrendCounts(Map<Long, Long> attempts, Map<Long, Long> anomalyAssessments, Map<Long, Long> sanctions) { }

	/**
	 * 구간 [from, to)를 intervalSeconds로 나눈 버킷 번호(0부터)별 건수.
	 * <ul>
	 *   <li>attempts: 그 버킷에 시작된 고유 예매 시도 수(시도의 첫 STARTED/ENTERED 시각 기준).</li>
	 *   <li>anomalyAssessments: 이 공연 참여자에게 저장된 이상 평가(점수가 탐지 기준 이상) 건수(평가 시각 기준).
	 *       사용자가 이 공연에 처음 시도한 시각보다 앞선 평가는 세지 않는다. AI 평가는 사용자 단위이므로
	 *       "이 공연에서 탐지된 건수"가 아니라 "이 공연 참여자가 참여 이후 이상으로 평가된 건수"다.</li>
	 *   <li>sanctions: 이 공연에서 실제로 적용된 제재 수. 고유 정책 결정 기준이며 그 결정의 첫 적용 시각으로 센다.</li>
	 * </ul>
	 */
	public TrendCounts trends(String serviceId, String concertId, LocalDateTime fromUtc, LocalDateTime toUtc,
			int intervalSeconds) {
		var params = concert(serviceId, concertId);
		params.put("from", UTC_TEXT.format(fromUtc));
		params.put("to", UTC_TEXT.format(toUtc));
		params.put("interval", intervalSeconds);
		params.put("threshold", RiskStatistics.ANOMALY_THRESHOLD);
		String bucket = "floor(timestampdiff(second, cast(:from as datetime(3)), %s) / :interval)";
		String within = "%1$s >= cast(:from as datetime(3)) and %1$s < cast(:to as datetime(3))";

		Map<Long, Long> attempts = counts("select " + bucket.formatted("s.started_at") + """
				 as bucket, count(*) as n
				from (select e.user_id, JSON_UNQUOTE(JSON_EXTRACT(e.metadata,'$.attemptId')) as attempt_id,
						min(e.occurred_at) as started_at
				""" + CONCERT_ATTEMPT_EVENTS + STARTED + " group by e.user_id, attempt_id) s where "
				+ within.formatted("s.started_at") + " group by bucket", params);

		Map<Long, Long> anomalies = counts("select " + bucket.formatted("a.assessed_at") + """
				 as bucket, count(*) as n
				from (select e.user_id, min(e.occurred_at) as first_at
				""" + CONCERT_ATTEMPT_EVENTS + STARTED + """
				 group by e.user_id) f join risk_assessment a on a.user_id = f.user_id
				where a.anomaly_score >= :threshold and a.assessed_at >= f.first_at and
				""" + " " + within.formatted("a.assessed_at") + " group by bucket", params);

		Map<Long, Long> sanctions = counts("select " + bucket.formatted("t.first_at") + """
				 as bucket, count(*) as n
				from (select decision_id, min(applied_at) as first_at from policy_application where
				""" + " " + CONCERT_SCOPE + " group by decision_id) t where " + within.formatted("t.first_at")
				+ " group by bucket", params);
		return new TrendCounts(attempts, anomalies, sanctions);
	}

	private Map<Long, Long> counts(String sql, Map<String, Object> params) {
		Map<Long, Long> result = new HashMap<>();
		jdbc.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
				result.put(rs.getLong("bucket"), rs.getLong("n")));
		return result;
	}

	// ------------------------------------------------------------------ helpers

	private static HashMap<String, Object> concert(String serviceId, String concertId) {
		return new HashMap<>(Map.of("serviceId", serviceId, "concertId", concertId));
	}

	private static String serviceScope(String column, Set<String> services, Map<String, Object> params) {
		if (services == null) return "1 = 1";
		List<String> hex = new ArrayList<>();
		for (String service : services) {
			hex.add(HexFormat.of().withUpperCase().formatHex(service.getBytes(StandardCharsets.UTF_8)));
		}
		params.put("services", services);
		params.put("serviceBytes", hex);
		return column + " in (:services) and hex(" + column + ") in (:serviceBytes)";
	}
}
