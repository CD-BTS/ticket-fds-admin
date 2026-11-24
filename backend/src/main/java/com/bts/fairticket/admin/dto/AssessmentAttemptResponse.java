package com.bts.fairticket.admin.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.entity.RiskAssessmentAttempt;

/**
 * AI 평가 시도 1건 (#67). status는 OK, INSUFFICIENT_DATA, INVALID 중 하나다.
 * OK가 아니면 anomalyScore와 assessmentId는 null이다(0점이나 정상으로 취급하지 않는다).
 * features는 그 시도에서 AI에 보낸 값이다. 시각은 UTC(Z)다.
 */
public record AssessmentAttemptResponse(Long attemptId, String status, String reason, String modelVersion,
        String calculatedAt, String createdAt, Long assessmentId, Double anomalyScore, AssessmentFeatures features) {

    public static AssessmentAttemptResponse from(RiskAssessmentAttempt attempt, RiskAssessment assessment) {
        AssessmentFeatures features = assessment != null
                ? AssessmentFeatures.ofRecorded(assessment.getBookingCount(), assessment.getAvgBookingInterval(),
                        assessment.getBookingIntervalVariance(), assessment.getCancelRate(),
                        assessment.getCardReuseCount(), assessment.getIpBookingDensity())
                : AssessmentFeatures.ofRecorded(attempt.getBookingCount(), attempt.getAvgBookingInterval(),
                        attempt.getBookingIntervalVariance(), attempt.getCancelRate(),
                        attempt.getCardReuseCount(), attempt.getIpBookingDensity());
        return new AssessmentAttemptResponse(attempt.getAttemptId(), attempt.getStatus(), attempt.getReason(),
                attempt.getModelVersion(), utc(attempt.getCalculatedAt()), utc(attempt.getCreatedAt()),
                assessment == null ? null : assessment.getAssessmentId(),
                assessment == null ? null : assessment.getAnomalyScore(), features);
    }

    static String utc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
