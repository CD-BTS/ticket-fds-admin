// src/test/java/com/bts/fairticket/policy/service/PolicyDecisionServiceTest.java
package com.bts.fairticket.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.entity.UserCredit;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.policy.dto.PolicyDecisionRequest;
import com.bts.fairticket.policy.dto.PolicyDecisionResponse;
import com.bts.fairticket.policy.entity.PolicyDecision;
import com.bts.fairticket.policy.repository.PolicyDecisionRepository;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.user.entity.UserAccount;
import com.bts.fairticket.user.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
class PolicyDecisionServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserCreditRepository userCreditRepository;

    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;

    @Mock
    private PolicyDecisionRepository policyDecisionRepository;

    @InjectMocks
    private PolicyDecisionService policyDecisionService;

    @Test
    @DisplayName("유효하지 않은 context 요청 시 INVALID_CONTEXT 예외 발생")
    void decidePolicy_InvalidContext() {
        PolicyDecisionRequest request = new PolicyDecisionRequest("INVALID_ACTION");

        assertThatThrownBy(() -> policyDecisionService.decidePolicy("user1", "service-1", request))
                .isInstanceOf(ApiException.class)
                .extracting("responseCode")
                .isEqualTo(ResponseCode.INVALID_CONTEXT);
    }

    @Test
    @DisplayName("AI 분석 이력이 없어도 신용 점수 기반으로 정상 판정 (anomalyScore=null)")
    void decidePolicy_WithoutAiScore_Success() {
        UserAccount user = new UserAccount("user1", "service-1", "ext-1");
        UserCredit userCredit = new UserCredit(user); // 기본 creditScore = 50

        given(userAccountRepository.findById("user1")).willReturn(Optional.of(user));
        given(userCreditRepository.findById("user1")).willReturn(Optional.of(userCredit));
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc("user1"))
                .willReturn(Optional.empty());

        given(policyDecisionRepository.save(any(PolicyDecision.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        PolicyDecisionRequest request = new PolicyDecisionRequest("QUEUE_JOIN");
        PolicyDecisionResponse response = policyDecisionService.decidePolicy("user1", "service-1", request);

        assertThat(response.action()).isEqualTo("ALLOW");
        assertThat(response.anomalyScore()).isNull();
    }

    // ---- #60: CAPTCHA 이상 점수 기준 0.40 → 0.50. Credit 조건, 분기 순서, DELAY(0.70) · RESTRICT(0.85) 기준은 그대로 ----

    @ParameterizedTest(name = "Credit {0}, anomalyScore {1} -> {2}")
    @CsvSource({
            // CAPTCHA 기준 0.50 경계 (Credit 정상)
            "50, 0.0,      ALLOW",
            "50, 0.40,     ALLOW",
            "50, 0.49,     ALLOW",
            "50, 0.499999, ALLOW",
            "50, 0.50,     REQUIRE_CAPTCHA",
            "50, 0.69,     REQUIRE_CAPTCHA",
            // DELAY 기준 0.70 (변경 없음)
            "50, 0.70,     DELAY",
            "50, 0.85,     DELAY",
            "50, 1.00,     DELAY",
            // Credit 조건 (변경 없음): 40 미만 CAPTCHA, 20 미만 DELAY
            "40, 0.0,      ALLOW",
            "39, 0.0,      REQUIRE_CAPTCHA",
            "39, 0.49,     REQUIRE_CAPTCHA",
            "20, 0.0,      REQUIRE_CAPTCHA",
            "20, 0.70,     DELAY",
            "19, 0.0,      DELAY",
            "19, 0.49,     DELAY",
            // RESTRICT: Credit 20 미만 그리고 0.85 이상 (변경 없음)
            "19, 0.84,     DELAY",
            "19, 0.85,     RESTRICT",
            "19, 1.00,     RESTRICT",
            "20, 0.85,     DELAY",
            "0,  0.85,     RESTRICT"
    })
    @DisplayName("[#60] 정책 분기: Credit과 이상 점수 조합별 조치")
    void decidePolicy_ByCreditAndAnomalyScore(int creditScore, double anomalyScore, String expectedAction) {
        PolicyDecisionResponse response = decide(creditScore, anomalyScore);

        assertThat(response.action()).isEqualTo(expectedAction);
        assertThat(response.anomalyScore()).isEqualTo(anomalyScore);
        assertThat(response.creditScore()).isEqualTo(creditScore);
    }

    @ParameterizedTest(name = "Credit {0}, AI 분석 이력 없음 -> {1}")
    @CsvSource({
            "100, ALLOW",
            "40,  ALLOW",
            "39,  REQUIRE_CAPTCHA",
            "20,  REQUIRE_CAPTCHA",
            "19,  DELAY",
            "0,   DELAY"
    })
    @DisplayName("[#60] AI 분석 이력이 없으면 Credit만으로 판정하고 RESTRICT는 나오지 않는다 (변경 없음)")
    void decidePolicy_WithoutAiScore_ByCredit(int creditScore, String expectedAction) {
        PolicyDecisionResponse response = decide(creditScore, null);

        assertThat(response.action()).isEqualTo(expectedAction);
        assertThat(response.anomalyScore()).isNull();
    }

    private PolicyDecisionResponse decide(int creditScore, Double anomalyScore) {
        UserAccount user = new UserAccount("user1", "service-1", "ext-1");
        UserCredit userCredit = new UserCredit(user);
        userCredit.updateCreditScore(creditScore, "NORMAL");

        given(userAccountRepository.findById("user1")).willReturn(Optional.of(user));
        given(userCreditRepository.findById("user1")).willReturn(Optional.of(userCredit));
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc("user1"))
                .willReturn(anomalyScore == null
                        ? Optional.empty()
                        : Optional.of(new RiskAssessment(user, anomalyScore, "fds-if-v4.0.0")));
        given(policyDecisionRepository.save(any(PolicyDecision.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        return policyDecisionService.decidePolicy("user1", "service-1", new PolicyDecisionRequest("QUEUE_JOIN"));
    }
}