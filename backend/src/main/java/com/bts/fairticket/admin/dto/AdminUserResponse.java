package com.bts.fairticket.admin.dto;

import java.time.LocalDateTime;

import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.entity.RiskAssessmentAttempt;
import com.bts.fairticket.user.dto.UserResponse;

/**
 * anomalyScore · lastAssessedAt은 가장 최근에 성공한 AI 평가의 값이고, 성공한 평가가 없으면 null이다(0이 아니다).
 * assessmentStatus(#67)는 가장 최근 평가 시도의 상태다: OK, INSUFFICIENT_DATA, INVALID, 또는 한 번도
 * 평가되지 않았으면 NOT_EVALUATED. 그래서 과거에 점수를 받은 사용자가 지금은 데이터 부족일 수 있다.
 */
public record AdminUserResponse(String userId, String serviceId, String externalUserId,
        String accountStatus, LocalDateTime createdAt, Integer creditScore, String tier,
        Double anomalyScore, LocalDateTime lastAssessedAt, String assessmentStatus) {

    public static AdminUserResponse from(UserResponse user, RiskAssessment assessment, RiskAssessmentAttempt latestAttempt) {
        return new AdminUserResponse(user.userId(), user.serviceId(), user.externalUserId(),
                user.accountStatus(), user.createdAt(), user.creditScore(), user.tier(),
                assessment == null ? null : assessment.getAnomalyScore(),
                assessment == null ? null : assessment.getAssessedAt(),
                assessmentStatus(latestAttempt, assessment != null));
    }

    /** #67 이전에 평가된 사용자는 시도 이력이 없다. 성공한 평가가 있으면 OK로 본다. */
    public static String assessmentStatus(RiskAssessmentAttempt latestAttempt, boolean hasAssessment) {
        if (latestAttempt != null) return latestAttempt.getStatus();
        return hasAssessment ? RiskAssessmentAttempt.OK : RiskAssessmentAttempt.NOT_EVALUATED;
    }
}
