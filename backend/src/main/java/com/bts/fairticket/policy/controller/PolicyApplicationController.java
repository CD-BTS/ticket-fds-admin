package com.bts.fairticket.policy.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bts.fairticket.common.auth.ApiKeyFilter;
import com.bts.fairticket.common.response.ApiResponse;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.policy.dto.PolicyApplicationRequest;
import com.bts.fairticket.policy.dto.PolicyApplicationResponse;
import com.bts.fairticket.policy.service.PolicyApplicationService;

import jakarta.validation.Valid;

/** 외부 티켓팅 서비스의 실제 정책 적용 기록 (#67). 서비스 키(X-API-Key)로 인증한다. */
@RestController
@RequestMapping("/api/v1/policy-applications")
public class PolicyApplicationController {

	private final PolicyApplicationService service;

	public PolicyApplicationController(PolicyApplicationService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<ApiResponse<PolicyApplicationResponse>> record(@Valid @RequestBody PolicyApplicationRequest request,
			@RequestAttribute(ApiKeyFilter.SERVICE_ATTRIBUTE) String authenticatedService) {
		var result = service.record(request, authenticatedService);
		var code = result.created() ? ResponseCode.CREATED : ResponseCode.SUCCESS;
		return ResponseEntity.status(code.getStatus()).body(ApiResponse.success(code, result.application()));
	}
}
