package com.bts.fairticket.concert.dto;

import java.util.Map;

import com.bts.fairticket.admin.dto.SanctionStatistics;

/**
 * 공연 상세와 통계. 값마다 세는 단위가 다르다 (#67).
 * <ul>
 *   <li>고유 시도: bookingAttemptCount = bookingSuccessCount + bookingFailureCount + bookingInProgressCount.
 *       시도의 마지막 단계 · 상태로 나눈다. 성공은 COMPLETED/SUCCEEDED, 실패는 상태가 REJECTED · FAILED ·
 *       ABANDONED · EXPIRED인 시도이고 failureBreakdown에 "단계/상태"별로 나뉜다. 그 밖은 아직 끝나지 않은 시도다.</li>
 *   <li>고유 예매: bookingCount, cancelCount (bookingId 기준).</li>
 *   <li>티켓: checkInCount (입장 완료 티켓).</li>
 *   <li>고유 사용자: bookingAttemptUserCount, assessedUserCount, unassessedUserCount, anomalyUserCount,
 *       usersByExpectedPolicy, expectedSanctionUserCount. 모집단은 이 공연에 예매를 시도한 사용자다.</li>
 * </ul>
 * anomalyUserCount는 참여자 중 최신 AI 이상 점수가 0.5 이상인 사용자 수다. AI 평가는 사용자 단위이므로
 * 다른 공연에서의 행동도 반영된다. 미평가 사용자는 unassessedUserCount로 따로 센다.
 * expectedSanctionUserCount는 현재 Credit과 점수로 계산한 예상 제재 대상이고, sanctions는 외부 서비스가
 * 실제로 적용했다고 보고한 제재다. 둘은 다른 값이다.
 */
public record ConcertDetailResponse(ConcertResponse concert, long bookingAttemptCount, long bookingAttemptUserCount,
        long bookingSuccessCount, long bookingFailureCount, long bookingInProgressCount,
        Map<String, Long> failureBreakdown, long bookingCount, long cancelCount, long checkInCount,
        long assessedUserCount, long unassessedUserCount, long anomalyUserCount, Double averageCreditScore,
        Map<String, Long> usersByExpectedPolicy, long expectedSanctionUserCount, SanctionStatistics sanctions) { }
