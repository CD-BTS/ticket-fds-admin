package com.bts.fairticket.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 외부 티켓팅 서비스가 정책을 실제로 적용했을 때 보내는 기록 (#67).
 * externalApplicationId는 외부 서비스가 만든 멱등 키다. 같은 키로 같은 내용을 다시 보내면 기존 기록을 돌려준다.
 * appliedAt은 UTC 오프셋이 붙은 ISO-8601(밀리초까지)이다.
 */
public record PolicyApplicationRequest(
		@NotBlank @Size(max = 50) String serviceId,
		@NotBlank @Size(max = 100) String externalApplicationId,
		@NotBlank @Size(max = 50) String userId,
		@NotNull @Positive Long decisionId,
		@NotBlank @Size(max = 50) String concertId,
		@NotBlank @Size(max = 30) String action,
		@NotBlank @Size(max = 20) String result,
		@NotBlank @Size(max = 40) String appliedAt) { }
