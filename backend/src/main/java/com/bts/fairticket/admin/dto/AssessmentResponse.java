package com.bts.fairticket.admin.dto;

import java.time.LocalDateTime;

/**
 * 성공한 AI 평가 1건. policyAction is the latest persisted policy action linked to this assessment, or null;
 * not an AI classification. features는 평가 당시 AI에 보낸 값이며 #67 이전 평가는 null이다.
 */
public record AssessmentResponse(Long assessmentId, Double anomalyScore, String policyAction,
        String modelVersion, LocalDateTime assessedAt, AssessmentFeatures features) {

    /** JPQL 생성자 표현식용. */
    public AssessmentResponse(Long assessmentId, Double anomalyScore, String policyAction, String modelVersion,
            LocalDateTime assessedAt, Integer bookingCount, Double avgBookingInterval, Double bookingIntervalVariance,
            Double cancelRate, Integer cardReuseCount, Integer ipBookingDensity) {
        this(assessmentId, anomalyScore, policyAction, modelVersion, assessedAt,
                AssessmentFeatures.ofRecorded(bookingCount, avgBookingInterval, bookingIntervalVariance,
                        cancelRate, cardReuseCount, ipBookingDensity));
    }
}
