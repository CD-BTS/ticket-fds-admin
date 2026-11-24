package com.bts.fairticket.user.dto;

import java.time.LocalDateTime;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME;

public record UserQuery(Integer page, Integer size, String sort,
		@Size(min = 1, max = 50) @Pattern(regexp = ".*\\S.*") String serviceId,
		@Size(min = 1, max = 50) @Pattern(regexp = ".*\\S.*") String userId,
		@Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String externalUserId,
		@Pattern(regexp = "ACTIVE|SUSPENDED") String accountStatus,
		@Size(min = 1, max = 20) @Pattern(regexp = ".*\\S.*") String tier,
		@Min(0) @Max(100) Integer minCreditScore, @Min(0) @Max(100) Integer maxCreditScore,
		@DateTimeFormat(iso = DATE_TIME) LocalDateTime createdFrom,
		@DateTimeFormat(iso = DATE_TIME) LocalDateTime createdTo,
		// #67: 최신 AI 이상 점수 범위(미평가 사용자는 범위 조건에 걸리지 않고 빠진다), 평가 여부, ID 부분 검색
		@jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0") Double minAnomalyScore,
		@jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0") Double maxAnomalyScore,
		Boolean assessed,
		@Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String keyword) { }
