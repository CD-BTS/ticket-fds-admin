package com.bts.fairticket.dashboard.dto;

import java.util.Map;

import com.bts.fairticket.admin.dto.SanctionStatistics;

/**
 * 관리자 요약. 사용자 수는 모두 고유 사용자다 (#67 추가 필드).
 * <ul>
 *   <li>assessedUsers · unassessedUsers: 성공한 AI 평가가 있는 / 없는 사용자.</li>
 *   <li>anomalyUsers: 최신 이상 점수가 0.5 이상인 사용자(AI 이상탐지). 미평가 사용자는 넣지 않는다.</li>
 *   <li>insufficientDataUsers: 가장 최근 평가 시도가 데이터 부족으로 끝난 사용자.</li>
 *   <li>usersByTier · usersByTierRatio: Credit 등급별 수와 비율(0~1). AI 이상탐지나 정책과는 별개다.</li>
 *   <li>usersByExpectedPolicy · expectedSanctionUsers: 현재 Credit과 최신 점수로 계산한 예상 조치별 사용자 수와,
 *       그중 ALLOW가 아닌 수. 실제 결정 · 적용이 아니다.</li>
 *   <li>sanctions: 외부 서비스가 실제로 적용했다고 보고한 제재.</li>
 * </ul>
 */
public record DashboardSummary(long totalUsers, long activeUsers, long suspendedUsers,
		long usersWithCredit, Double averageCreditScore, Map<String, Long> usersByTier,
		long totalEvents, Map<String, Long> eventsByType,
		long assessedUsers, long unassessedUsers, long anomalyUsers, long insufficientDataUsers,
		Map<String, Double> usersByTierRatio, Map<String, Long> usersByExpectedPolicy, long expectedSanctionUsers,
		SanctionStatistics sanctions) { }
