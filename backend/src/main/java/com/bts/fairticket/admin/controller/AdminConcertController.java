package com.bts.fairticket.admin.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.bts.fairticket.common.auth.AdminScope;
import com.bts.fairticket.common.auth.ApiKeyFilter;
import com.bts.fairticket.common.response.*;
import com.bts.fairticket.concert.dto.*;
import com.bts.fairticket.concert.service.ConcertService;
import com.bts.fairticket.user.dto.UserQuery;

@RestController
@RequestMapping("/api/v1/admin/concerts")
public class AdminConcertController {
    private final ConcertService concerts;
    public AdminConcertController(ConcertService concerts) { this.concerts = concerts; }

    @GetMapping
    public ApiResponse<PageResponse<com.bts.fairticket.concert.dto.ConcertListItemResponse>> list(
            @RequestParam @Size(max = 50) @Pattern(regexp = "\\S+") String serviceId,
            @Valid @ModelAttribute ConcertQuery query, @RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
        return ApiResponse.success(concerts.list(serviceId, query, scope));
    }

    @GetMapping("/{concertId}")
    public ApiResponse<ConcertDetailResponse> detail(
            @PathVariable @Size(max = 50) @Pattern(regexp = "\\S+") String concertId,
            @RequestParam @Size(max = 50) @Pattern(regexp = "\\S+") String serviceId,
            @RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
        return ApiResponse.success(concerts.detail(serviceId, concertId, scope));
    }

    // 시간대별 추이(#67). from · to는 UTC 오프셋이 붙은 ISO-8601, intervalSeconds는 60 · 300 · 600 · 3600.
    @GetMapping("/{concertId}/trends")
    public ApiResponse<com.bts.fairticket.admin.dto.ConcertTrendResponse> trends(
            @PathVariable @Size(max = 50) @Pattern(regexp = "\\S+") String concertId,
            @RequestParam @Size(max = 50) @Pattern(regexp = "\\S+") String serviceId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer intervalSeconds,
            @RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
        return ApiResponse.success(concerts.trends(serviceId, concertId, from, to, intervalSeconds, scope));
    }

    @GetMapping("/{concertId}/users")
    public ApiResponse<PageResponse<ConcertUserResponse>> users(
            @PathVariable @Size(max = 50) @Pattern(regexp = "\\S+") String concertId,
            @RequestParam @Size(max = 50) @Pattern(regexp = "\\S+") String serviceId,
            @Valid @ModelAttribute UserQuery query, @RequestAttribute(ApiKeyFilter.ADMIN_ATTRIBUTE) AdminScope scope) {
        return ApiResponse.success(concerts.users(serviceId, concertId, query, scope));
    }
}
