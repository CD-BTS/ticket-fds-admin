// src/main/java/com/bts/fairticket/policy/dto/PolicyDecisionRequest.java
package com.bts.fairticket.policy.dto;

import jakarta.validation.constraints.NotBlank;

public record PolicyDecisionRequest(
    @NotBlank(message = "context는 필수입니다.")
    String context,

    // 정책을 요청한 공연 (#67, 선택). 없으면 공연 정보 없이 기록한다.
    @jakarta.validation.constraints.Size(max = 50, message = "concertId는 50자 이하여야 합니다.")
    String concertId
) {
    public PolicyDecisionRequest(String context) {
        this(context, null);
    }
}