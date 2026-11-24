package com.bts.fairticket.credit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bts.fairticket.credit.dto.CreditEvaluationContext;
import com.bts.fairticket.credit.entity.CreditHistory;
import com.bts.fairticket.credit.entity.UserCard;
import com.bts.fairticket.credit.entity.UserCredit;
import com.bts.fairticket.credit.policy.CreditCalculator;
import com.bts.fairticket.credit.repository.BookingHistoryRepository;
import com.bts.fairticket.credit.repository.BookingHistoryRepository.BookingStats;
import com.bts.fairticket.credit.repository.CreditHistoryRepository;
import com.bts.fairticket.credit.repository.UserCardRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.user.entity.UserAccount;
import com.bts.fairticket.user.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
class CreditEvaluationServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private UserCreditRepository userCreditRepository;
    @Mock
    private BookingHistoryRepository bookingHistoryRepository;
    @Mock
    private UserCardRepository userCardRepository;
    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;
    @Mock
    private CreditHistoryRepository creditHistoryRepository;
    @Mock
    private com.bts.fairticket.credit.repository.CreditCalculationRepository creditCalculationRepository;

    @Spy
    private CreditCalculator creditCalculator = new CreditCalculator();

    @InjectMocks
    private CreditEvaluationService creditEvaluationService;

    @Test
    @DisplayName("관람 완료 10회 우수 유저: 점수 50점에서 75점으로 갱신되고 이력이 적재된다")
    void evaluateAndApplyCredit_GoodUser() {
        // Given
        String userId = "good-user-1";
        UserAccount user = new UserAccount(userId, "service-1", "ext-good");
        UserCredit userCredit = new UserCredit(user); // 초기 50점

        given(userAccountRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));
        given(userCreditRepository.findByIdForUpdate(userId)).willReturn(Optional.of(userCredit));

        // 관람 이력: COMPLETED 10건, NO_SHOW 0건, AUTO_CANCELLED 0건
        BookingStats stats = new BookingStats() {
            public Long getCompletedCount() { return 10L; }
            public Long getNoShowCount() { return 0L; }
            public Long getAutoCancelledCount() { return 0L; }
        };
        given(bookingHistoryRepository.getBookingStatsByUserId(userId)).willReturn(stats);
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc(userId))
                .willReturn(Optional.empty());
        given(userCardRepository.findByUser_UserId(userId)).willReturn(List.of());

        CreditEvaluationContext context = new CreditEvaluationContext("정기 평가", 0, 2.5);

        // When
        var summary = creditEvaluationService.evaluateAndApplyCredit(userId, context);

        // Then
        // BaseCredit = 100 * (10+5)/(10+10) = 75점, 감점 0 -> 최종 75점 (NORMAL)
        assertThat(summary.newScore()).isEqualTo(75);
        assertThat(summary.grade()).isEqualTo("NORMAL");
        assertThat(userCredit.getCreditScore()).isEqualTo(75);

        verify(creditHistoryRepository).save(any(CreditHistory.class));
        verify(userCreditRepository).save(userCredit);
    }

    @Test
    @DisplayName("카드 4계정 공유 및 AI 이상치 탐지 시 고위험군(HIGH_RISK)으로 감점 갱신")
    void evaluateAndApplyCredit_HighRiskUser() {
        // Given
        String userId = "risky-user-1";
        UserAccount user = new UserAccount(userId, "service-1", "ext-risky");
        UserCredit userCredit = new UserCredit(user);

        given(userAccountRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));
        given(userCreditRepository.findByIdForUpdate(userId)).willReturn(Optional.of(userCredit));
        given(bookingHistoryRepository.getBookingStatsByUserId(userId)).willReturn(null);

        // AI 이상도 0.75: 40 * (2 * 0.75 - 1) = 20점 감점
        RiskAssessment risk = new RiskAssessment(user, 0.75, "HIGH_RISK");
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc(userId))
                .willReturn(Optional.of(risk));

        // 카드 4계정 공유 (-15점 감점)
        UserCard card = new UserCard(user, "hash-1234", "현대", "1234-****-****-1234");
        given(userCardRepository.findByUser_UserId(userId)).willReturn(List.of(card));
        given(userCardRepository.countDistinctUserByCardHash("hash-1234")).willReturn(4L);

        CreditEvaluationContext context = CreditEvaluationContext.defaultContext();

        // When
        var summary = creditEvaluationService.evaluateAndApplyCredit(userId, context);

        // Then
        // BaseCredit 50 - DynamicPenalty 35 (AI 20 + Card 15) = 15점 (HIGH_RISK)
        assertThat(summary.newScore()).isEqualTo(15);
        assertThat(summary.dynamicPenalty()).isEqualTo(35.0);
        assertThat(summary.grade()).isEqualTo("HIGH_RISK");
        assertThat(summary.penaltyPolicy()).contains("후순위");

        verify(creditHistoryRepository).save(any(CreditHistory.class));
    }

    @Test
    @DisplayName("[#60] 정상 판정 구간의 이상 점수(0.49)는 감점하지 않는다: 룰 감점만 적용")
    void evaluateAndApplyCredit_ScoreBelowDetectionThresholdHasNoAiPenalty() {
        UserCredit userCredit = newUser("below-threshold-user", 0.49);

        // IP 집중도 5 + 요청 간격 분산 5 = 10
        var summary = creditEvaluationService.evaluateAndApplyCredit("below-threshold-user",
                new CreditEvaluationContext("평가", 5, 0.5));

        assertThat(summary.dynamicPenalty()).isEqualTo(10.0);
        assertThat(summary.newScore()).isEqualTo(40);
        assertThat(summary.grade()).isEqualTo("NORMAL");
        assertThat(userCredit.getDynamicPenalty()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("[#60] 탐지된 이상 점수(0.85)의 AI 감점 28점과 룰 감점 10점을 더한다")
    void evaluateAndApplyCredit_AiAndRulePenaltyAreSummed() {
        UserCredit userCredit = newUser("summed-user", 0.85);

        var summary = creditEvaluationService.evaluateAndApplyCredit("summed-user",
                new CreditEvaluationContext("평가", 5, 0.5));

        assertThat(summary.dynamicPenalty()).isCloseTo(38.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(summary.newScore()).isEqualTo(12);
        assertThat(summary.grade()).isEqualTo("HIGH_RISK");
        assertThat(userCredit.getCreditScore()).isEqualTo(12);
    }

    @Test
    @DisplayName("[#60] 요청 간격 분산이 null이면 분산 감점을 하지 않는다 (1.0 미만이면 5점, 1.0이면 0점)")
    void evaluateAndApplyCredit_NullIntervalVarianceIsNotPenalized() {
        newUser("variance-null", null);
        assertThat(creditEvaluationService.evaluateAndApplyCredit("variance-null",
                new CreditEvaluationContext("평가", 0, null)).dynamicPenalty()).isEqualTo(0.0);

        newUser("variance-low", null);
        assertThat(creditEvaluationService.evaluateAndApplyCredit("variance-low",
                new CreditEvaluationContext("평가", 0, 0.99)).dynamicPenalty()).isEqualTo(5.0);

        newUser("variance-boundary", null);
        assertThat(creditEvaluationService.evaluateAndApplyCredit("variance-boundary",
                new CreditEvaluationContext("평가", 0, 1.0)).dynamicPenalty()).isEqualTo(0.0);
    }

    /** 관람 이력과 카드가 없는 신규 사용자. anomalyScore가 null이면 RiskAssessment가 없는 사용자다. */
    // ---- #63: 늦게 실행된 이전 AI 평가의 콜백은 Credit을 바꾸지 않는다 ----

    @Test
    @DisplayName("[#63] 더 새로운 평가가 저장된 뒤 실행된 이전 평가의 콜백은 Credit과 이력을 바꾸지 않는다")
    void evaluateAfterAssessment_SkipsWhenANewerAssessmentExists() {
        String userId = "stale-callback";
        UserAccount user = new UserAccount(userId, "service-1", "ext-stale");
        UserCredit userCredit = new UserCredit(user);
        RiskAssessment newer = org.mockito.Mockito.mock(RiskAssessment.class);
        given(newer.getAssessmentId()).willReturn(9L);
        given(userAccountRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));
        given(userCreditRepository.findByIdForUpdate(userId)).willReturn(Optional.of(userCredit));
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc(userId))
                .willReturn(Optional.of(newer));

        // 평가 5의 콜백: IP 시도 9회, 분산 0.1(둘 다 감점 조건)을 들고 왔지만 최신 평가는 9다.
        var result = creditEvaluationService.evaluateAfterAssessment(userId,
                new CreditEvaluationContext("AI 이상도 평가 반영", 9, 0.1), 5L);

        assertThat(result).isEmpty();
        assertThat(userCredit.getCreditScore()).isEqualTo(50);
        assertThat(userCredit.getDynamicPenalty()).isNull();
        verify(userCreditRepository, org.mockito.Mockito.never()).save(any());
        verify(creditHistoryRepository, org.mockito.Mockito.never()).save(any());
        // 잠금 순서는 그대로 user_account → user_credit이다.
        var order = org.mockito.Mockito.inOrder(userAccountRepository, userCreditRepository);
        order.verify(userAccountRepository).findByIdForUpdate(userId);
        order.verify(userCreditRepository).findByIdForUpdate(userId);
    }

    @Test
    @DisplayName("[#63] 여전히 최신인 평가의 콜백은 그 평가의 점수와 룰 감점 입력값으로 Credit을 갱신한다")
    void evaluateAfterAssessment_AppliesWhenStillLatest() {
        String userId = "latest-callback";
        UserCredit userCredit = newUser(userId, null);
        RiskAssessment latest = org.mockito.Mockito.mock(RiskAssessment.class);
        given(latest.getAssessmentId()).willReturn(5L);
        given(latest.getAnomalyScore()).willReturn(0.75);
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc(userId))
                .willReturn(Optional.of(latest));

        var result = creditEvaluationService.evaluateAfterAssessment(userId,
                new CreditEvaluationContext("AI 이상도 평가 반영", 5, null), 5L);

        // AI 감점 40 * (2 * 0.75 - 1) = 20, IP 감점 5 → 50 - 25 = 25
        assertThat(result).isPresent();
        assertThat(result.get().newScore()).isEqualTo(25);
        assertThat(userCredit.getDynamicPenalty()).isEqualTo(25.0);
        verify(creditHistoryRepository).save(any(CreditHistory.class));
    }

    // ---- #63: 카드 해시 입력 규칙 (db/manual/063 백필 SQL과 같은 규칙) ----

    @Test
    @DisplayName("[#63] 카드 해시: 앞뒤 공백·탭·CR·LF만 떼고, serviceId 길이를 포함해 조합이 섞이지 않는다")
    void cardHash_NormalizesIdentifierAndIsUnambiguous() throws Exception {
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("3:svc:pay-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(BookingHistoryService.cardHash("svc", java.util.Map.of("paymentIdentifier", "pay-1"))).isEqualTo(expected);
        assertThat(BookingHistoryService.cardHash("svc", java.util.Map.of("paymentIdentifier", " \t\r\npay-1 \n\r\t")))
                .isEqualTo(expected);
        // 안쪽 공백은 식별자의 일부다.
        assertThat(BookingHistoryService.cardHash("svc", java.util.Map.of("paymentIdentifier", "pay 1"))).isNotEqualTo(expected);
        // 길이를 넣지 않으면 "a:b" + ":" + "c"와 "a" + ":" + "b:c"가 같은 문자열이 된다.
        assertThat(BookingHistoryService.cardHash("a:b", java.util.Map.of("paymentIdentifier", "c")))
                .isNotEqualTo(BookingHistoryService.cardHash("a", java.util.Map.of("paymentIdentifier", "b:c")));
        // 같은 식별자라도 서비스가 다르면 다른 카드다.
        assertThat(BookingHistoryService.cardHash("other", java.util.Map.of("paymentIdentifier", "pay-1"))).isNotEqualTo(expected);
        // 식별자가 없거나, 문자열이 아니거나, 떼어낸 뒤 비어 있으면 카드를 만들지 않는다.
        assertThat(BookingHistoryService.cardHash("svc", java.util.Map.of())).isNull();
        assertThat(BookingHistoryService.cardHash("svc", null)).isNull();
        assertThat(BookingHistoryService.cardHash("svc", java.util.Map.of("paymentIdentifier", 1234))).isNull();
        assertThat(BookingHistoryService.cardHash("svc", java.util.Map.of("paymentIdentifier", " \t\r\n"))).isNull();
        assertThat(BookingHistoryService.cardHash(null, java.util.Map.of("paymentIdentifier", "pay-1"))).isNull();
    }

    private UserCredit newUser(String userId, Double anomalyScore) {
        UserAccount user = new UserAccount(userId, "service-1", "ext-" + userId);
        UserCredit userCredit = new UserCredit(user);
        given(userAccountRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));
        given(userCreditRepository.findByIdForUpdate(userId)).willReturn(Optional.of(userCredit));
        given(bookingHistoryRepository.getBookingStatsByUserId(userId)).willReturn(null);
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc(userId))
                .willReturn(anomalyScore == null
                        ? Optional.empty()
                        : Optional.of(new RiskAssessment(user, anomalyScore, "fds-if-v4.0.0")));
        given(userCardRepository.findByUser_UserId(userId)).willReturn(List.of());
        return userCredit;
    }

    private static BookingStats stats(long completed, long noShow, long autoCancelled) {
        return new BookingStats() {
            public Long getCompletedCount() { return completed; }
            public Long getNoShowCount() { return noShow; }
            public Long getAutoCancelledCount() { return autoCancelled; }
        };
    }

    @Test
    @DisplayName("수동 평가는 적용한 DynamicPenalty를 저장한다")
    void evaluateAndApplyCredit_StoresDynamicPenalty() {
        String userId = "penalty-user";
        UserAccount user = new UserAccount(userId, "service-1", "ext-penalty");
        UserCredit userCredit = new UserCredit(user);
        given(userAccountRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));
        given(userCreditRepository.findByIdForUpdate(userId)).willReturn(Optional.of(userCredit));
        given(bookingHistoryRepository.getBookingStatsByUserId(userId)).willReturn(null);
        given(riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc(userId))
                .willReturn(Optional.empty());
        given(userCardRepository.findByUser_UserId(userId)).willReturn(List.of());

        // IP 집중도 5 + 요청 간격 분산 5 = 10
        creditEvaluationService.evaluateAndApplyCredit(userId, new CreditEvaluationContext("평가", 5, 0.5));

        assertThat(userCredit.getCreditScore()).isEqualTo(40);
        assertThat(userCredit.getDynamicPenalty()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("관람 이력 자동 갱신은 저장된 감점을 유지하고 이벤트와 연결된 이력을 한 건 남긴다")
    void applyAttendanceChange_KeepsStoredPenalty() {
        String userId = "auto-user";
        UserAccount user = new UserAccount(userId, "service-1", "ext-auto");
        UserCredit userCredit = new UserCredit(user);
        userCredit.updateCreditScore(40, "NORMAL", 10.0);
        given(userCreditRepository.findById(userId)).willReturn(Optional.of(userCredit));
        given(bookingHistoryRepository.getBookingStatsByUserId(userId)).willReturn(stats(1, 0, 0));

        var summary = creditEvaluationService.applyAttendanceChange(user, null, "관람 완료").orElseThrow();

        // 54.545 - 10 = 44.545 → 45
        assertThat(summary.oldScore()).isEqualTo(40);
        assertThat(summary.newScore()).isEqualTo(45);
        assertThat(userCredit.getDynamicPenalty()).isEqualTo(10.0);
        verify(creditHistoryRepository).save(any(CreditHistory.class));
        // 카드·AI 감점을 다시 계산하지 않는다.
        org.mockito.Mockito.verifyNoInteractions(userCardRepository, riskAssessmentRepository);
    }

    @Test
    @DisplayName("평가 이력이 없는 기존 사용자는 감점 0으로 계산한다")
    void applyAttendanceChange_NeverEvaluatedUsesZeroPenalty() {
        String userId = "new-user";
        UserAccount user = new UserAccount(userId, "service-1", "ext-new");
        UserCredit userCredit = new UserCredit(user);
        given(userCreditRepository.findById(userId)).willReturn(Optional.of(userCredit));
        given(creditHistoryRepository.existsByUser_UserId(userId)).willReturn(false);
        given(bookingHistoryRepository.getBookingStatsByUserId(userId)).willReturn(stats(0, 1, 0));

        var summary = creditEvaluationService.applyAttendanceChange(user, null, "노쇼").orElseThrow();

        // 100 * 5 / (2 + 10) = 41.67 → 42
        assertThat(summary.newScore()).isEqualTo(42);
        assertThat(userCredit.getDynamicPenalty()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("감점을 알 수 없는 기존 평가 사용자는 점수를 역산하지 않고 그대로 둔다")
    void applyAttendanceChange_UnknownPenaltyLeavesScoreUnchanged() {
        String userId = "legacy-user";
        UserAccount user = new UserAccount(userId, "service-1", "ext-legacy");
        UserCredit userCredit = new UserCredit(user);
        userCredit.updateCreditScore(35, "SUSPICIOUS");
        given(userCreditRepository.findById(userId)).willReturn(Optional.of(userCredit));
        given(creditHistoryRepository.existsByUser_UserId(userId)).willReturn(true);

        assertThat(creditEvaluationService.applyAttendanceChange(user, null, "관람 완료")).isEmpty();

        assertThat(userCredit.getCreditScore()).isEqualTo(35);
        assertThat(userCredit.getDynamicPenalty()).isNull();
        verify(creditHistoryRepository, org.mockito.Mockito.never()).save(any(CreditHistory.class));
    }
}
