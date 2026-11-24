package com.bts.fairticket.credit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.credit.entity.CreditHistory;
import com.bts.fairticket.credit.entity.UserCredit;
import com.bts.fairticket.credit.repository.CreditHistoryRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.user.entity.UserAccount;
import com.bts.fairticket.user.repository.UserAccountRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("mysql")
class CreditApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserCreditRepository userCreditRepository;

    @Autowired
    private CreditHistoryRepository creditHistoryRepository;

    // #67: Credit API는 임의의 Bearer 값이 아니라 서비스 키(X-API-Key)로 인증한다.
    private static final String SERVICE_KEY = com.bts.fairticket.user.ApiKeyTestSupport.randomKey();

    @org.springframework.test.context.DynamicPropertySource
    static void keys(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("BTS_API_KEY_HASHES", () ->
                com.bts.fairticket.user.ApiKeyTestSupport.configuration(java.util.Map.of("service-1", SERVICE_KEY)));
    }

@Test
    @DisplayName("[통합] 실제 데이터 적재 후 GET /credit 호출 시 올바른 응답을 반환한다")
    void getCredit_E2E() throws Exception {
        // 1. user 생성 및 save 후 반환된 영속 엔티티를 savedUser 변수로 받음
        UserAccount user = new UserAccount("credit-e2e-user-1", "service-1", "ext-user-1");
        UserAccount savedUser = userAccountRepository.saveAndFlush(user);

        // 2. savedUser를 넘겨서 UserCredit 생성 및 저장
        userCreditRepository.saveAndFlush(new UserCredit(savedUser));

        mockMvc.perform(get("/api/v1/users/{userId}/credit", savedUser.getUserId())
                        .header("X-API-Key", SERVICE_KEY)
                        .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(savedUser.getUserId()))
                .andExpect(jsonPath("$.data.creditScore").value(50))
                .andExpect(jsonPath("$.data.tier").value("BASE"));
    }

    @Test
    @DisplayName("[통합] CreditHistory 적재 후 페이징 조회 검증")
    void getCreditHistory_E2E() throws Exception {
        UserAccount user = new UserAccount("credit-e2e-user-2", "service-1", "ext-user-2");
        user = userAccountRepository.saveAndFlush(user);
        UserAccount savedUser = userAccountRepository.saveAndFlush(user);
        creditHistoryRepository.save(new CreditHistory(user, null, 70, 60, "예매 취소 페널티"));
        creditHistoryRepository.save(new CreditHistory(user, null, 60, 50, "추가 취소 페널티"));

        mockMvc.perform(get("/api/v1/users/{userId}/credit-history", user.getUserId())
                        .header("X-API-Key", SERVICE_KEY)
                        .param("page", "0")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.histories.length()").value(2));
    }
}
