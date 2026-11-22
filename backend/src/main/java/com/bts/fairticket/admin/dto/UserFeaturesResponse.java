package com.bts.fairticket.admin.dto;

import java.time.LocalDateTime;
import com.bts.fairticket.risk.dto.UserCalculatedFeatures;
import com.bts.fairticket.risk.service.FeatureAggregationService;

/**
 * AI 입력 Feature 6개의 계산값. 조회 시점 기준 최근 {@code windowDays}일 행동 이벤트로 계산하며 저장하지 않는다.
 * 계산할 수 없는 값(시도 2건 미만의 간격 평균·분산, 예매 0건의 취소율)은 null이다.
 */
public record UserFeaturesResponse(String userId, LocalDateTime calculatedAt, int windowDays,
        Integer bookingCount, Double avgBookingInterval, Double bookingIntervalVariance,
        Double cancelRate, Integer cardReuseCount, Integer ipBookingDensity) {

    public static UserFeaturesResponse of(String userId, LocalDateTime calculatedAt, UserCalculatedFeatures f) {
        return new UserFeaturesResponse(userId, calculatedAt, FeatureAggregationService.WINDOW_DAYS,
                f.getBookingCount(), f.getAvgBookingInterval(), f.getBookingIntervalVariance(),
                f.getCancelRate(), f.getCardReuseCount(), f.getIpBookingDensity());
    }
}
