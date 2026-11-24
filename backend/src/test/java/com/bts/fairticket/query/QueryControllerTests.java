package com.bts.fairticket.query;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.bts.fairticket.common.auth.ApiKeyConfiguration;
import com.bts.fairticket.common.exception.GlobalExceptionHandler;
import com.bts.fairticket.common.response.PageResponse;
import com.bts.fairticket.admin.controller.AdminUserController;
import com.bts.fairticket.user.controller.UserQueryController;
import com.bts.fairticket.dashboard.controller.DashboardController;
import com.bts.fairticket.user.service.UserQueryService;
import com.bts.fairticket.behavior.service.UserEventQueryService;
import com.bts.fairticket.dashboard.service.DashboardQueryService;
import com.bts.fairticket.user.ApiKeyTestSupport;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({UserQueryController.class, AdminUserController.class, DashboardController.class})
@Import({ApiKeyConfiguration.class, GlobalExceptionHandler.class, com.bts.fairticket.admin.service.AdminUserQueryService.class})
class QueryControllerTests {
	private static final String SERVICE_KEY = ApiKeyTestSupport.randomKey();
	private static final String ADMIN = ApiKeyTestSupport.randomKey();
	private static final String GLOBAL = ApiKeyTestSupport.randomKey();
	@DynamicPropertySource
	static void keys(DynamicPropertyRegistry registry) {
		registry.add("BTS_API_KEY_HASHES", () -> ApiKeyTestSupport.configuration(Map.of("a", SERVICE_KEY)));
		registry.add("BTS_ADMIN_API_KEYS", () -> "[{\"hash\":\"" + ApiKeyTestSupport.hash(ADMIN)
				+ "\",\"services\":[\"a\",\"b\"],\"allServices\":false},{\"hash\":\""
				+ ApiKeyTestSupport.hash(GLOBAL) + "\",\"services\":[],\"allServices\":true}]");
	}
	@Autowired MockMvc mvc;
	@MockitoBean UserQueryService users;
	@MockitoBean UserEventQueryService events;
	@MockitoBean DashboardQueryService dashboard;
	@MockitoBean com.bts.fairticket.user.service.UserService details;
	@MockitoBean com.bts.fairticket.user.repository.UserAccountRepository accounts;
	@MockitoBean com.bts.fairticket.risk.repository.RiskAssessmentRepository assessments;
	@MockitoBean com.bts.fairticket.policy.repository.PolicyDecisionRepository policies;
	@MockitoBean com.bts.fairticket.admin.repository.AdminAssessmentQueryRepository history;
	@MockitoBean com.bts.fairticket.risk.service.FeatureAggregationService featureService;
	@MockitoBean com.bts.fairticket.credit.service.BookingHistoryQueryService bookings;
	@MockitoBean java.time.Clock clock;
	// #67: 관리자 조회가 읽는 저장소(평가 시도 이력, Credit 산출 근거)
	@MockitoBean com.bts.fairticket.risk.repository.RiskAssessmentAttemptRepository attempts;
	@MockitoBean com.bts.fairticket.credit.service.CreditQueryService credits;
	@MockitoBean com.bts.fairticket.credit.repository.UserCreditRepository userCredits;
	@MockitoBean com.bts.fairticket.credit.repository.CreditCalculationRepository calculations;

	@Test
	void authenticationCoversAllNewRoutesAndNormalizesPaths() throws Exception {
		for (String path : List.of("/api/v1/users", "/api/v1/users/id/events", "/api/v1/admin/users",
				"/api/v1/admin/summary", "/api/v1/admin/users/id", "/api/v1/admin/users/id/events",
				"/api/v1/admin/users/id/assessments", "/api/v1/admin/users/id/features",
				"/api/v1/users/id/bookings", "/api/v1/admin/users/id/bookings", "/api/v1/admin/users;x=1", "/api/v1/admin/summary;x=1")) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHORIZED"));
			mvc.perform(get(path).header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
			mvc.perform(get(path).header("X-API-Key", SERVICE_KEY, SERVICE_KEY)).andExpect(status().isUnauthorized());
		}
		mvc.perform(get("/context/api/v1/admin/users").contextPath("/context")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/admin/users").header("X-API-Key", SERVICE_KEY))
				.andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
		mvc.perform(get("/api/v1/admin/summary").header("X-API-Key", SERVICE_KEY)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/users").header("X-API-Key", GLOBAL)).andExpect(status().isForbidden());
		verifyNoInteractions(users, events, dashboard);
	}

	// #57: 요약 API는 /api/v1/admin/summary 하나뿐이다. 이전 경로는 어떤 키로도 응답하지 않는다.
	@Test
	void formerDashboardSummaryPathNoLongerExists() throws Exception {
		mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isNotFound());
		for (String key : List.of(ADMIN, GLOBAL, SERVICE_KEY)) {
			mvc.perform(get("/api/v1/dashboard/summary").header("X-API-Key", key)).andExpect(status().isNotFound());
		}
		verifyNoInteractions(dashboard);
	}

	@Test
	void derivesServiceScopesFromCredentialsAndRejectsUnauthorizedSelection() throws Exception {
		when(users.find(any(), any())).thenReturn(PageResponse.of(List.of(), 0, 20, 0));
		mvc.perform(get("/api/v1/users").header("X-API-Key", SERVICE_KEY)).andExpect(status().isOk())
				.andExpect(jsonPath("data.content").isEmpty()).andExpect(jsonPath("data.totalElements").value(0));
		verify(users).find(any(), eq(Set.of("a")));
		mvc.perform(get("/api/v1/admin/users").header("X-API-Key", ADMIN)).andExpect(status().isOk());
		verify(users).find(any(), eq(Set.of("a", "b")));
		mvc.perform(get("/api/v1/admin/users").param("serviceId", "c").header("X-API-Key", ADMIN)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/summary").param("serviceId", "c").header("X-API-Key", ADMIN)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/users").header("X-API-Key", GLOBAL)).andExpect(status().isOk());
		verify(users).find(any(), isNull());
		mvc.perform(get("/api/v1/admin/summary").header("X-API-Key", ADMIN)).andExpect(status().isOk());
		verify(dashboard).summarize(Set.of("a", "b"));
		mvc.perform(get("/api/v1/admin/summary").header("X-API-Key", GLOBAL)).andExpect(status().isOk());
		verify(dashboard).summarize(null);
	}

	@Test
	void invalidTypesAndFiltersUseExistingErrorEnvelope() throws Exception {
		for (Map.Entry<String, String> param : Map.of("page", "abc", "accountStatus", "UNKNOWN", "minCreditScore", "-1",
				"maxCreditScore", "101", "createdFrom", "invalid", "serviceId", " ").entrySet()) {
			mvc.perform(get("/api/v1/admin/users").param(param.getKey(), param.getValue()).header("X-API-Key", ADMIN))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
		}
		mvc.perform(get("/api/v1/users").param("serviceId", "b").header("X-API-Key", SERVICE_KEY)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/users/id/events").param("eventType", "INVALID").header("X-API-Key", SERVICE_KEY)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/users/id/events").param("from", "2026-01-01T00:00:00").header("X-API-Key", SERVICE_KEY))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(users, events, dashboard);
	}

	@Test
	void adminRoutesEnforceScopeBeforeQueryingAndValidateParameters() throws Exception {
		when(accounts.findById("foreign")).thenReturn(java.util.Optional.of(
				new com.bts.fairticket.user.entity.UserAccount("foreign", "c", "external")));
		for (String suffix : List.of("", "/events", "/assessments", "/features")) {
			mvc.perform(get("/api/v1/admin/users/foreign" + suffix).header("X-API-Key", ADMIN))
					.andExpect(status().isNotFound()).andExpect(jsonPath("code").value("USER_NOT_FOUND"));
			mvc.perform(get("/api/v1/admin/users/missing" + suffix).header("X-API-Key", ADMIN))
					.andExpect(status().isNotFound());
			mvc.perform(get("/api/v1/admin/users/foreign" + suffix).header("X-API-Key", SERVICE_KEY))
					.andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/v1/admin/users/id/events").header("X-API-Key", ADMIN).param("eventType", "INVALID"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/admin/users/id/assessments").header("X-API-Key", ADMIN).param("page", "abc"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(details, events, history, policies, featureService);
	}

	@Test
	void adminFeaturesReturnsCalculatedValuesForUsersInScope() throws Exception {
		when(clock.instant()).thenReturn(java.time.Instant.parse("2026-11-21T00:00:00Z"));
		when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
		when(accounts.findById("mine")).thenReturn(java.util.Optional.of(
				new com.bts.fairticket.user.entity.UserAccount("mine", "a", "external")));
		when(featureService.aggregateFeatures(eq("mine"), any())).thenReturn(
				com.bts.fairticket.risk.dto.UserCalculatedFeatures.builder().bookingCount(4)
						.avgBookingInterval(12.5).bookingIntervalVariance(6.25).cancelRate(0.5)
						.cardReuseCount(1).ipBookingDensity(3).build());
		mvc.perform(get("/api/v1/admin/users/mine/features").header("X-API-Key", ADMIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("data.userId").value("mine"))
				.andExpect(jsonPath("data.windowDays").value(30))
				.andExpect(jsonPath("data.bookingCount").value(4))
				.andExpect(jsonPath("data.avgBookingInterval").value(12.5))
				.andExpect(jsonPath("data.bookingIntervalVariance").value(6.25))
				.andExpect(jsonPath("data.cancelRate").value(0.5))
				.andExpect(jsonPath("data.cardReuseCount").value(1))
				.andExpect(jsonPath("data.ipBookingDensity").value(3));
	}
}
