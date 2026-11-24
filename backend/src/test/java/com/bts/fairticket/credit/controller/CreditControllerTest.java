package com.bts.fairticket.credit.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.dto.CreditHistoryResponse;
import com.bts.fairticket.credit.dto.CreditResponse;
import com.bts.fairticket.credit.service.CreditQueryService;

@WebMvcTest(CreditController.class)
@AutoConfigureMockMvc(addFilters = false)
class CreditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreditQueryService creditQueryService;

    // API 키 필터가 인증한 서비스(#67). 이 테스트는 필터를 끄므로 요청 속성으로 직접 넣는다.
    private static final String SERVICE = "service-1";

    @Test
    @DisplayName("GET /credit - 정상 조회 시 200 OK와 신용 정보를 반환한다")
    void getCredit_Success() throws Exception {
        CreditResponse response = new CreditResponse("bts_user_001", 45, "LOW", "2026-10-08T05:30:00Z");
        given(creditQueryService.getCredit("bts_user_001", SERVICE)).willReturn(response);

        mockMvc.perform(get("/api/v1/users/{userId}/credit", "bts_user_001")
                        .requestAttr(com.bts.fairticket.common.auth.ApiKeyFilter.SERVICE_ATTRIBUTE, SERVICE)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON_200"))
                .andExpect(jsonPath("$.data.userId").value("bts_user_001"))
                .andExpect(jsonPath("$.data.creditScore").value(45))
                .andExpect(jsonPath("$.data.tier").value("LOW"));
    }

    @Test
    @DisplayName("GET /credit - 인증된 서비스가 없으면(임의의 Bearer 값만 있으면) 401 Unauthorized를 반환한다")
    void getCredit_MissingAuth_ThrowsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/{userId}/credit", "bts_user_001")
                        .header("Authorization", "Bearer anything")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /credit - 존재하지 않는 사용자 조회 시 404 Not Found를 반환한다")
    void getCredit_UserNotFound_Throws404() throws Exception {
        given(creditQueryService.getCredit("unknown_user", SERVICE))
                .willThrow(new ApiException(ResponseCode.USER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/users/{userId}/credit", "unknown_user")
                        .requestAttr(com.bts.fairticket.common.auth.ApiKeyFilter.SERVICE_ATTRIBUTE, SERVICE)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /credit-history - 정상 페이징 조회 시 200 OK와 리스트를 반환한다")
    void getCreditHistory_Success() throws Exception {
        var item = new CreditHistoryResponse.HistoryItem(
                301L, 102L, 50, 45, "반복적인 예매 취소", "2026-10-08T06:00:00Z");
        var response = new CreditHistoryResponse("bts_user_001", List.of(item), 0, 20, 1, 1);

        given(creditQueryService.getCreditHistory(eq("bts_user_001"), eq(SERVICE), anyInt(), anyInt(), anyString(), anyString()))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/users/{userId}/credit-history", "bts_user_001")
                        .requestAttr(com.bts.fairticket.common.auth.ApiKeyFilter.SERVICE_ATTRIBUTE, SERVICE)
                        .param("page", "0")
                        .param("size", "20")
                        .param("startDate", "2026-10-01")
                        .param("endDate", "2026-10-10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.histories[0].historyId").value(301))
                .andExpect(jsonPath("$.data.histories[0].scoreBefore").value(50))
                .andExpect(jsonPath("$.data.histories[0].scoreAfter").value(45));
    }
}
