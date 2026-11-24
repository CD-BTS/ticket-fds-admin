package com.bts.fairticket.admin.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bts.fairticket.policy.service.PolicyDecisionService;

/**
 * 사용자 집합의 AI 평가 · Credit · 예상 정책 통계 (#67). 모두 고유 사용자 수다.
 * <ul>
 *   <li>assessedUsers: 성공한 AI 평가가 한 건이라도 있는 사용자. unassessedUsers: 없는 사용자.</li>
 *   <li>anomalyUsers: 최신 이상 점수가 탐지 기준(0.5) 이상인 사용자. 미평가 사용자는 이상에도 정상에도 넣지 않는다.</li>
 *   <li>usersByExpectedPolicy: 현재 Credit과 최신 점수로 지금 정책을 요청하면 받을 조치별 사용자 수.
 *       실제로 내려진 결정이나 적용된 제재가 아니다. expectedSanctionUsers는 그중 ALLOW가 아닌 사용자 수다.</li>
 * </ul>
 */
public record RiskStatistics(long users, long assessedUsers, long unassessedUsers, long anomalyUsers,
		Double averageCreditScore, Map<String, Long> usersByExpectedPolicy, long expectedSanctionUsers) {

	/** AI 모델의 탐지 기준. anomalyScore가 이 값 이상이면 모델이 이상으로 판정한 것이다(isAnomaly와 같다). */
	public static final double ANOMALY_THRESHOLD = 0.5;

	/**
	 * (Credit, 이상 점수의 1/100 구간)별 사용자 수. scoreBucket은 floor(anomalyScore * 100)이고 미평가면 null이다.
	 * 예상 정책은 구간 값으로 PolicyDecisionService의 판정 코드를 그대로 호출해 구한다. 판정 임계값(0.50, 0.70, 0.85)이
	 * 0.01의 배수이므로 구간으로 묶어도 판정이 달라지지 않는다. 임계값을 0.01보다 잘게 바꾸면 이 집계도 바꿔야 한다.
	 */
	public record Bucket(int creditScore, Integer scoreBucket, long users) { }

	public static RiskStatistics from(List<Bucket> buckets) {
		long users = 0, assessed = 0, anomaly = 0, sanction = 0, creditSum = 0;
		Map<String, Long> policies = new LinkedHashMap<>();
		for (String action : List.of("ALLOW", "REQUIRE_CAPTCHA", "DELAY", "RESTRICT")) {
			policies.put(action, 0L);
		}
		for (Bucket bucket : buckets) {
			users += bucket.users();
			creditSum += bucket.creditScore() * bucket.users();
			Double score = bucket.scoreBucket() == null ? null : bucket.scoreBucket() / 100.0;
			if (score != null) {
				assessed += bucket.users();
				if (score >= ANOMALY_THRESHOLD) {
					anomaly += bucket.users();
				}
			}
			String action = PolicyDecisionService.expectedAction(bucket.creditScore(), score);
			policies.merge(action, bucket.users(), Long::sum);
			if (!"ALLOW".equals(action)) {
				sanction += bucket.users();
			}
		}
		return new RiskStatistics(users, assessed, users - assessed, anomaly,
				users == 0 ? null : (double) creditSum / users, policies, sanction);
	}
}
