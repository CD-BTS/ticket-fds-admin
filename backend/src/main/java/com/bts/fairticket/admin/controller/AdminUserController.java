package com.bts.fairticket.admin.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import com.bts.fairticket.common.auth.AdminScope;
import com.bts.fairticket.common.auth.ApiKeyFilter;
import com.bts.fairticket.common.response.ApiResponse;
import com.bts.fairticket.common.response.PageResponse;
import com.bts.fairticket.user.dto.UserQuery;
import com.bts.fairticket.admin.dto.*;
import com.bts.fairticket.admin.service.AdminUserQueryService;
import com.bts.fairticket.behavior.dto.EventQuery;
import com.bts.fairticket.behavior.dto.EventResponse;
import com.bts.fairticket.credit.dto.BookingHistoryQuery;
import com.bts.fairticket.credit.dto.BookingHistoryResponse;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
	private final AdminUserQueryService users;
	public AdminUserController(AdminUserQueryService users) { this.users = users; }

	@GetMapping
	public ApiResponse<PageResponse<AdminUserResponse>> list(@Valid @ModelAttribute UserQuery query,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.find(query, scope));
	}

	@GetMapping("/{userId}")
	public ApiResponse<AdminUserDetailResponse> detail(@PathVariable @NotBlank @Size(max = 50) String userId,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.detail(userId, scope));
	}

	@GetMapping("/{userId}/events")
	public ApiResponse<PageResponse<EventResponse>> events(@PathVariable @NotBlank @Size(max = 50) String userId,
			@Valid @ModelAttribute EventQuery query,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.events(userId, query, scope));
	}

	// 티켓 단위 관람 이력(#56)
	@GetMapping("/{userId}/bookings")
	public ApiResponse<PageResponse<BookingHistoryResponse>> bookings(@PathVariable @NotBlank @Size(max = 50) String userId,
			@Valid @ModelAttribute BookingHistoryQuery query,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.bookings(userId, query, scope));
	}

	@GetMapping("/{userId}/assessments")
	public ApiResponse<PageResponse<AssessmentResponse>> assessments(@PathVariable @NotBlank @Size(max = 50) String userId,
			@Valid @ModelAttribute AssessmentQuery query,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.assessments(userId, query, scope));
	}

	// 평가 시도 이력(#67): 성공한 평가와 데이터 부족 · INVALID로 끝난 시도를 함께 본다.
	@GetMapping("/{userId}/assessment-attempts")
	public ApiResponse<PageResponse<com.bts.fairticket.admin.dto.AssessmentAttemptResponse>> assessmentAttempts(
			@PathVariable @NotBlank @Size(max = 50) String userId,
			@Valid @ModelAttribute AssessmentQuery query,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.assessmentAttempts(userId, query, scope));
	}

	// Credit 변경 이력과 산출 근거(#67). startDate · endDate는 UTC 날짜(yyyy-MM-dd)다.
	@GetMapping("/{userId}/credit-history")
	public ApiResponse<PageResponse<com.bts.fairticket.admin.dto.AdminCreditHistoryResponse>> creditHistory(
			@PathVariable @NotBlank @Size(max = 50) String userId,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String startDate,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String endDate,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.creditHistory(userId, page, size, startDate, endDate, scope));
	}

	@GetMapping("/{userId}/features")
	public ApiResponse<UserFeaturesResponse> features(@PathVariable @NotBlank @Size(max = 50) String userId,
			@RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
		return ApiResponse.success(users.features(userId, scope));
	}
}
