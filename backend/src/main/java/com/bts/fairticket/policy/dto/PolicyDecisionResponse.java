// src/main/java/com/bts/fairticket/policy/dto/PolicyDecisionResponse.java
package com.bts.fairticket.policy.dto;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import com.bts.fairticket.policy.entity.PolicyDecision;

public record PolicyDecisionResponse(
    Long decisionId,
    String userId,
    String context,
    Integer creditScore,
    Double anomalyScore,
    String action,
    String reason,
    String decidedAt,
    String concertId
) {
    public static PolicyDecisionResponse from(PolicyDecision decision) {
        String formattedDecidedAt = null;
        if (decision.getDecidedAt() != null) {
            // DB의 시각은 UTC다. 변환 없이 +09:00을 붙이던 표기를 UTC(Z)로 바로잡았다 (#67).
            formattedDecidedAt = decision.getDecidedAt()
                    .atOffset(java.time.ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        return new PolicyDecisionResponse(
            decision.getDecisionId(),
            decision.getUser().getUserId(),
            decision.getContext(),
            decision.getCreditScoreAtDecision(),
            decision.getAnomalyScoreAtDecision(),
            decision.getAction(),
            decision.getReason(),
            formattedDecidedAt,
            decision.getConcertId()
        );
    }
}