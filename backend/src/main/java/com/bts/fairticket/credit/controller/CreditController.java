package com.bts.fairticket.credit.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bts.fairticket.common.auth.ApiKeyFilter;
import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ApiResponse;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.dto.CreditHistoryResponse;
import com.bts.fairticket.credit.dto.CreditResponse;
import com.bts.fairticket.credit.service.CreditQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 외부 서비스용 Credit 조회 API. 다른 사용자 API와 같이 서비스 키(X-API-Key)로 인증하고,
 * 인증된 서비스에 속한 사용자만 조회할 수 있다 (#67). 이전에는 임의의 Bearer 값만 있으면 통과했다.
 * 관리자 조회는 /api/v1/admin/users/{userId}/credit-history를 쓴다.
 */
@RestController
@RequestMapping("/api/v1/users/{userId}")
@RequiredArgsConstructor
public class CreditController {

    private final CreditQueryService creditQueryService;

    @GetMapping("/credit")
    public ResponseEntity<ApiResponse<CreditResponse>> getCredit(
            @RequestAttribute(value = ApiKeyFilter.SERVICE_ATTRIBUTE, required = false) String authenticatedService,
            @PathVariable String userId) {

        CreditResponse response = creditQueryService.getCredit(userId, authenticated(authenticatedService));

        return ResponseEntity.ok(ApiResponse.success(ResponseCode.SUCCESS, response, "관람 신용 정보를 조회했습니다."));
    }

    @GetMapping("/credit-history")
    public ResponseEntity<ApiResponse<CreditHistoryResponse>> getCreditHistory(
            @RequestAttribute(value = ApiKeyFilter.SERVICE_ATTRIBUTE, required = false) String authenticatedService,
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        CreditHistoryResponse response = creditQueryService.getCreditHistory(
                userId, authenticated(authenticatedService), page, size, startDate, endDate);

        return ResponseEntity.ok(ApiResponse.success(ResponseCode.SUCCESS, response, "신용 점수 변동 이력을 조회했습니다."));
    }

    // API 키 필터가 인증한 서비스가 없으면(필터를 거치지 않은 요청) 조회를 허용하지 않는다.
    private static String authenticated(String authenticatedService) {
        if (authenticatedService == null || authenticatedService.isBlank()) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        return authenticatedService;
    }
}
