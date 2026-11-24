package com.bts.fairticket.admin.dto;

import java.util.List;

/**
 * 공연의 시간대별 추이 (#67). from(포함) ~ to(제외)를 intervalSeconds로 나눈 버킷이며 빈 버킷도 0으로 들어 있다.
 * 시각은 UTC(Z)다. 세 값은 단위가 다르다.
 * <ul>
 *   <li>attemptCount: 그 버킷에 시작된 고유 예매 시도 수.</li>
 *   <li>anomalyAssessmentCount: 이 공연 참여자에게 저장된 이상 평가(점수 0.5 이상) 건수. 사용자가 이 공연에
 *       처음 시도한 뒤의 평가만 센다. AI 평가는 사용자 단위이고 기본 60초 주기라 60초보다 잘게 나눌 수 없다.</li>
 *   <li>sanctionAppliedCount: 이 공연에서 실제로 적용된 제재 수(고유 정책 결정, 첫 적용 시각 기준).
 *       외부 서비스가 적용 기록을 보내지 않으면 0이다(sanctionRecorded로 구분).</li>
 * </ul>
 */
public record ConcertTrendResponse(String serviceId, String concertId, String from, String to, int intervalSeconds,
        boolean sanctionRecorded, List<Bucket> buckets) {

    public record Bucket(String startsAt, long attemptCount, long anomalyAssessmentCount, long sanctionAppliedCount) { }
}
