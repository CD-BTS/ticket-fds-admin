package com.bts.fairticket.risk.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * ticket-fds-ai POST /predict 요청의 features 6개와 1:1 대응 (preprocessing.py FEATURE_SPECS).
 * 계산 불가 값은 0 등으로 바꾸지 않고 null 그대로 전달한다 (AI가 중앙값으로 대체).
 */
@Getter
@Builder
public class UserCalculatedFeatures {
    private Integer bookingCount;             // 최근 30일 고유 예매 시도(BOOKING_ATTEMPT attemptId) 수 (null 불가, 기본 0)
    private Double avgBookingInterval;        // 연속 시도 시작 간격 평균(초) (시도 2건 미만 시 null)
    private Double bookingIntervalVariance;   // 연속 시도 시작 간격 분산(초^2, 모분산) (시도 2건 미만 시 null)
    private Double cancelRate;                // 예매(BOOKING) 완료 대비 취소 비율 (완료 0건 시 null)
    private Integer cardReuseCount;           // 동일 결제수단을 쓴 최대 고유 계정 수, 본인 포함 (null 불가, 기본 1)
    private Integer ipBookingDensity;         // 동일 IP로 예매한 최대 고유 계정 수, 본인 포함 (null 불가, 기본 1)
}
