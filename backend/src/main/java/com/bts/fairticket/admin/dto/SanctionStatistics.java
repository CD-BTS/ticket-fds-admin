package com.bts.fairticket.admin.dto;

import java.util.Map;

/**
 * 외부 서비스가 실제로 적용했다고 보고한 정책의 통계 (#67). policy_application 기준이며 예상 정책과는 다른 값이다.
 * <ul>
 *   <li>recorded: 해당 서비스(들)가 정책 적용 기록을 한 번이라도 보낸 적이 있는지. false면 "제재가 없었다"가 아니라
 *       "기록이 없다"는 뜻이고, 이때 아래 값은 모두 null이다(0으로 추정하지 않는다).</li>
 *   <li>appliedUserCount: 제재가 실제로 적용된 고유 사용자 수. 정책별 사용자 수의 합이 아니다
 *       (한 사용자가 여러 정책을 받아도 1명).</li>
 *   <li>byPolicy: 정책별 고유 사용자 수와 적용 횟수. 적용 횟수는 고유 정책 결정(decisionId) 수다.
 *       CAPTCHA 요구와 그 통과 · 실패 기록은 같은 결정이므로 1회로 센다.</li>
 *   <li>captcha: CAPTCHA 인증 결과(고유 정책 결정 수). 제재 적용 횟수와 별개의 통계다.</li>
 * </ul>
 */
public record SanctionStatistics(boolean recorded, Long appliedUserCount, Map<String, PolicyCount> byPolicy,
		CaptchaOutcome captcha) {

	public record PolicyCount(long userCount, long applicationCount) { }

	/** challenged: CAPTCHA가 요구된 결정 수, passed · failed: 그중 통과 · 실패가 보고된 결정 수. */
	public record CaptchaOutcome(long challenged, long passed, long failed) { }

	public static SanctionStatistics notRecorded() {
		return new SanctionStatistics(false, null, null, null);
	}
}
