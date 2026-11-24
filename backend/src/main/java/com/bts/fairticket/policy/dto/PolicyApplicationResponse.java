package com.bts.fairticket.policy.dto;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.bts.fairticket.policy.entity.PolicyApplication;

/** appliedAt은 UTC(Z)이다. */
public record PolicyApplicationResponse(Long applicationId, String serviceId, String externalApplicationId,
		String userId, Long decisionId, String concertId, String action, String result, String appliedAt) {

	private static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

	public static PolicyApplicationResponse from(PolicyApplication application) {
		return new PolicyApplicationResponse(application.getApplicationId(), application.getServiceId(),
				application.getExternalApplicationId(), application.getUserId(), application.getDecisionId(),
				application.getConcertId(), application.getAction(), application.getResult(),
				application.getAppliedAt().atOffset(ZoneOffset.UTC).format(UTC_MILLIS));
	}
}
