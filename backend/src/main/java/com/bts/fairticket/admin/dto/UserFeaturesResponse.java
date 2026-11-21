package com.bts.fairticket.admin.dto;

import java.time.LocalDateTime;
import com.bts.fairticket.risk.dto.UserCalculatedFeatures;

/** AI 입력 Feature 계산값. 조회 시점 기준 최근 {@code windowDays}일 행동 이벤트로 계산하며 저장하지 않는다. */
public record UserFeaturesResponse(String userId, LocalDateTime calculatedAt, int windowDays,
        Integer bookingCount, Integer cancelCount, Double cancelRate, Double avgBookingInterval,
        Integer shortIntervalBookingCount, Double attendanceRate, Double avgBookingDuration) {
    public static final int WINDOW_DAYS = 30;

    public static UserFeaturesResponse of(String userId, LocalDateTime calculatedAt, UserCalculatedFeatures f) {
        return new UserFeaturesResponse(userId, calculatedAt, WINDOW_DAYS, f.getBookingCount(),
                f.getCancelCount(), f.getCancelRate(), f.getAvgBookingInterval(),
                f.getShortIntervalBookingCount(), f.getAttendanceRate(), f.getAvgBookingDuration());
    }
}
