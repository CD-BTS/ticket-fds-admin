package com.bts.fairticket.admin.dto;

/**
 * AI 평가 요청에 실제로 넣은 Feature 6개 (#67). 조회 시점에 다시 계산한 값이 아니다.
 * 계산할 수 없어 AI에 null로 보낸 값은 null이다. 0으로 바꾸지 않는다.
 */
public record AssessmentFeatures(Integer bookingCount, Double avgBookingInterval, Double bookingIntervalVariance,
        Double cancelRate, Integer cardReuseCount, Integer ipBookingDensity) {

    /** bookingCount는 AI에 항상 보내는 값이다. 없으면 Feature를 기록하지 않은 행(#67 이전)이므로 null을 돌려준다. */
    public static AssessmentFeatures ofRecorded(Integer bookingCount, Double avgBookingInterval,
            Double bookingIntervalVariance, Double cancelRate, Integer cardReuseCount, Integer ipBookingDensity) {
        return bookingCount == null ? null : new AssessmentFeatures(bookingCount, avgBookingInterval,
                bookingIntervalVariance, cancelRate, cardReuseCount, ipBookingDensity);
    }
}
