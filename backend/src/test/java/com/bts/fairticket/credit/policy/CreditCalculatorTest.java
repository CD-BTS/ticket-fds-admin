package com.bts.fairticket.credit.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.bts.fairticket.credit.policy.CreditCalculator.CalculationInput;
import com.bts.fairticket.credit.policy.CreditCalculator.CalculationResult;
import com.bts.fairticket.credit.policy.CreditCalculator.CreditGrade;

class CreditCalculatorTest {

    private final CreditCalculator calculator = new CreditCalculator();

    @Test
    @DisplayName("[시나리오 1] 신규 정상 사용자: 관람 이력 없음, 감점 0 -> 50점 (NORMAL)")
    void calculate_NewUser() {
        CalculationInput input = new CalculationInput(0, 0, 0, 0.0, 0.0);

        CalculationResult result = calculator.calculate(input);

        assertThat(result.baseCredit()).isEqualTo(50.0);
        assertThat(result.dynamicPenalty()).isEqualTo(0.0);
        assertThat(result.finalCreditScore()).isEqualTo(50);
        assertThat(result.grade()).isEqualTo(CreditGrade.NORMAL);
    }

    @Test
    @DisplayName("[시나리오 2] 빠른 정상 사용자: 베이스 50점, 룰 감점 5점 -> 45점 (NORMAL)")
    void calculate_FastNormalUser() {
        // AI 감점 0, 간격 분산 룰 감점 5점 가정
        CalculationInput input = new CalculationInput(0, 0, 0, 0.0, 5.0);

        CalculationResult result = calculator.calculate(input);

        assertThat(result.baseCredit()).isEqualTo(50.0);
        assertThat(result.dynamicPenalty()).isEqualTo(5.0);
        assertThat(result.finalCreditScore()).isEqualTo(45);
        assertThat(result.grade()).isEqualTo(CreditGrade.NORMAL);
    }

    @Test
    @DisplayName("[시나리오 3] 이상 행동 의심 사용자: AI 이상도 0.75 (감점 20점) -> 30점 (SUSPICIOUS)")
    void calculate_SuspiciousUser() {
        // AI 이상도 0.75: 40 * (2 * 0.75 - 1) = 20점 감점
        CalculationInput input = new CalculationInput(0, 0, 0, 0.75, 0.0);

        CalculationResult result = calculator.calculate(input);

        assertThat(result.baseCredit()).isEqualTo(50.0);
        assertThat(result.dynamicPenalty()).isEqualTo(20.0);
        assertThat(result.finalCreditScore()).isEqualTo(30);
        assertThat(result.grade()).isEqualTo(CreditGrade.SUSPICIOUS);
        assertThat(result.penaltyPolicy()).contains("CAPTCHA 추가 인증");
    }

    @Test
    @DisplayName("[시나리오 4] 다계정 매크로 의심 사용자: AI 0.75(20점) + 카드/IP 룰(15점) 감점 35점 -> 15점 (HIGH_RISK)")
    void calculate_MacroHighRiskUser() {
        CalculationInput input = new CalculationInput(0, 0, 0, 0.75, 15.0);

        CalculationResult result = calculator.calculate(input);

        assertThat(result.baseCredit()).isEqualTo(50.0);
        assertThat(result.dynamicPenalty()).isEqualTo(35.0);
        assertThat(result.finalCreditScore()).isEqualTo(15);
        assertThat(result.grade()).isEqualTo(CreditGrade.HIGH_RISK);
        assertThat(result.penaltyPolicy()).contains("후순위 배치");
    }

    @Test
    @DisplayName("[시나리오 5] 관람 이력이 우수한 사용자: 관람 완료 10회, 노쇼 0회, 미입금 0회 -> Base 약 75점")
    void calculate_GoodHistoryUser() {
        // A = 10 -> BaseCredit = 100 * (10 + 5) / (10 + 10) = 1500 / 20 = 75.0
        CalculationInput input = new CalculationInput(10, 0, 0, 0.0, 5.0);

        CalculationResult result = calculator.calculate(input);

        assertThat(result.baseCredit()).isCloseTo(75.0, offset(0.01));
        assertThat(result.dynamicPenalty()).isEqualTo(5.0);
        assertThat(result.finalCreditScore()).isEqualTo(70);
        assertThat(result.grade()).isEqualTo(CreditGrade.NORMAL);
    }

    @Test
    @DisplayName("[경계값] 감점이 BaseCredit보다 큰 경우 음수가 되지 않고 0점으로 클램핑된다")
    void calculate_ClampedToZero() {
        CalculationInput input = new CalculationInput(0, 5, 2, 1.0, 30.0);

        CalculationResult result = calculator.calculate(input);

        assertThat(result.finalCreditScore()).isZero();
        assertThat(result.grade()).isEqualTo(CreditGrade.HIGH_RISK);
    }

    // ---- #60: AI 감점 = 40 * max(0, 2 * anomalyScore - 1) ----

    @ParameterizedTest(name = "anomalyScore {0} -> AI 감점 {1}, Credit {2} ({3})")
    @CsvSource({
            // 정상 판정 구간(0.5 미만)과 탐지 기준(0.5)은 감점 없음
            "0.0,      0.0, 50, NORMAL",
            "0.25,     0.0, 50, NORMAL",
            "0.49,     0.0, 50, NORMAL",
            "0.499999, 0.0, 50, NORMAL",
            "0.50,     0.0, 50, NORMAL",
            // 0.5 초과부터 차등 감점
            "0.60,     8.0, 42, NORMAL",
            "0.70,    16.0, 34, SUSPICIOUS",
            "0.75,    20.0, 30, SUSPICIOUS",
            "0.85,    28.0, 22, SUSPICIOUS",
            "0.90,    32.0, 18, HIGH_RISK",
            "1.00,    40.0, 10, HIGH_RISK"
    })
    @DisplayName("[#60] 신규 사용자의 AI 감점: 0.5 미만은 0점, 0.5 ~ 1.0 은 0 ~ 40점")
    void calculate_AiPenaltyByAnomalyScore(double anomalyScore, double expectedPenalty, int expectedCredit,
            CreditGrade expectedGrade) {
        CalculationResult result = calculator.calculate(new CalculationInput(0, 0, 0, anomalyScore, 0.0));

        assertThat(result.dynamicPenalty()).isCloseTo(expectedPenalty, offset(1e-9));
        assertThat(result.finalCreditScore()).isEqualTo(expectedCredit);
        assertThat(result.grade()).isEqualTo(expectedGrade);
    }

    @Test
    @DisplayName("[#60] anomalyScore가 null이면 AI 감점 없이 룰 감점만 적용한다")
    void calculate_NullAnomalyScore() {
        CalculationResult withoutRule = calculator.calculate(new CalculationInput(0, 0, 0, null, 0.0));
        assertThat(withoutRule.dynamicPenalty()).isEqualTo(0.0);
        assertThat(withoutRule.finalCreditScore()).isEqualTo(50);
        assertThat(withoutRule.grade()).isEqualTo(CreditGrade.NORMAL);

        CalculationResult withRule = calculator.calculate(new CalculationInput(0, 0, 0, null, 15.0));
        assertThat(withRule.dynamicPenalty()).isEqualTo(15.0);
        assertThat(withRule.finalCreditScore()).isEqualTo(35);
    }

    @Test
    @DisplayName("[#60] anomalyScore는 0 ~ 1로 제한한다: 1 초과는 최대 40점, 음수는 0점")
    void calculate_AnomalyScoreIsClamped() {
        assertThat(calculator.calculate(new CalculationInput(0, 0, 0, 1.5, 0.0)).dynamicPenalty()).isEqualTo(40.0);
        assertThat(calculator.calculate(new CalculationInput(0, 0, 0, 100.0, 0.0)).dynamicPenalty()).isEqualTo(40.0);
        assertThat(calculator.calculate(new CalculationInput(0, 0, 0, -0.3, 0.0)).dynamicPenalty()).isEqualTo(0.0);
    }

    @ParameterizedTest(name = "anomalyScore {0} + 룰 {1} -> 감점 {2}, Credit {3}")
    @CsvSource({
            "0.49,  5.0,  5.0, 45",   // 정상 판정: 룰 감점만
            "0.50, 15.0, 15.0, 35",
            "0.75,  5.0, 25.0, 25",   // AI 20 + 룰 5
            "0.85, 15.0, 43.0,  7",   // AI 28 + 룰 15
            "1.00, 30.0, 70.0,  0",   // AI 40 + 룰 30 = 전체 최대 70
            "1.00, 50.0, 70.0,  0",   // 룰은 30으로 제한
            "0.00, 50.0, 30.0, 20"
    })
    @DisplayName("[#60] AI 감점과 룰 감점은 더하고, 룰 최대 30 · 전체 최대 70은 그대로다")
    void calculate_AiAndRulePenaltyAreSummed(double anomalyScore, double rulePenalty, double expectedPenalty,
            int expectedCredit) {
        CalculationResult result = calculator.calculate(new CalculationInput(0, 0, 0, anomalyScore, rulePenalty));

        assertThat(result.dynamicPenalty()).isCloseTo(expectedPenalty, offset(1e-9));
        assertThat(result.finalCreditScore()).isEqualTo(expectedCredit);
    }

    @Test
    @DisplayName("[#60] 반올림과 등급 경계는 그대로다: 39.5 → 40 (NORMAL), 19.5 → 20 (SUSPICIOUS)")
    void calculate_RoundingAtGradeBoundaries() {
        CalculationResult normal = calculator.calculate(new CalculationInput(0, 0, 0, 0.0, 10.5));
        assertThat(normal.finalCreditScore()).isEqualTo(40);
        assertThat(normal.grade()).isEqualTo(CreditGrade.NORMAL);

        CalculationResult suspiciousUpper = calculator.calculate(new CalculationInput(0, 0, 0, 0.0, 10.6));
        assertThat(suspiciousUpper.finalCreditScore()).isEqualTo(39);
        assertThat(suspiciousUpper.grade()).isEqualTo(CreditGrade.SUSPICIOUS);

        // AI 20 (0.75) + 룰 10.5 = 30.5 → 19.5 → 20
        CalculationResult suspiciousLower = calculator.calculate(new CalculationInput(0, 0, 0, 0.75, 10.5));
        assertThat(suspiciousLower.finalCreditScore()).isEqualTo(20);
        assertThat(suspiciousLower.grade()).isEqualTo(CreditGrade.SUSPICIOUS);

        CalculationResult highRisk = calculator.calculate(new CalculationInput(0, 0, 0, 0.75, 10.6));
        assertThat(highRisk.finalCreditScore()).isEqualTo(19);
        assertThat(highRisk.grade()).isEqualTo(CreditGrade.HIGH_RISK);
    }

    @ParameterizedTest(name = "A={0}, N={1}, C={2} -> Credit {3}")
    @CsvSource({
            "0, 0, 0, 50",    // 초기 Credit
            "1, 0, 0, 55",    // 100 * 6 / 11 = 54.5
            "5, 0, 0, 67",    // 100 * 10 / 15 = 66.7
            "10, 0, 0, 75",
            "0, 1, 0, 42",    // 100 * 5 / 12 = 41.7
            "0, 2, 0, 36",    // 100 * 5 / 14 = 35.7
            "0, 0, 2, 38",    // 100 * 5 / 13 = 38.5
            "10, 2, 1, 59"    // 100 * 15 / 25.5 = 58.8
    })
    @DisplayName("[회귀] 관람 이력 기반 BaseCredit은 바뀌지 않았다 (감점 없음, 정상 판정 구간의 anomalyScore)")
    void calculate_AttendanceHistoryUnchanged(long completed, long noShow, long autoCancelled, int expectedCredit) {
        for (Double anomalyScore : new Double[] { null, 0.0, 0.3, 0.5 }) {
            CalculationResult result = calculator.calculate(
                    new CalculationInput(completed, noShow, autoCancelled, anomalyScore, 0.0));

            assertThat(result.dynamicPenalty()).isEqualTo(0.0);
            assertThat(result.finalCreditScore()).isEqualTo(expectedCredit);
        }
    }

    @Test
    @DisplayName("저장된 감점으로 계산한 결과는 같은 이력·같은 감점의 수동 계산 결과와 같다")
    void calculateWithPenalty_MatchesCalculate() {
        long[][] histories = { {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {10, 2, 1}, {0, 5, 3}, {37, 4, 2} };
        double[][] penalties = { {0.0, 0.0}, {0.25, 5.0}, {0.75, 15.0}, {1.0, 30.0}, {0.3333, 10.0}, {0.6, 0.0}, {0.85, 5.0} };
        for (long[] history : histories) {
            for (double[] penalty : penalties) {
                CalculationResult manual = calculator.calculate(
                        new CalculationInput(history[0], history[1], history[2], penalty[0], penalty[1]));
                CalculationResult automatic = calculator.calculateWithPenalty(
                        history[0], history[1], history[2], manual.dynamicPenalty());

                assertThat(automatic).isEqualTo(manual);
            }
        }
    }

    @Test
    @DisplayName("저장된 감점 적용: 반올림과 0점 하한은 기존 계산과 같다")
    void calculateWithPenalty_RoundingAndFloor() {
        // 1회 관람: 54.545 → 55, 감점 10이면 44.545 → 45
        assertThat(calculator.calculateWithPenalty(1, 0, 0, 0.0).finalCreditScore()).isEqualTo(55);
        assertThat(calculator.calculateWithPenalty(1, 0, 0, 10.0).finalCreditScore()).isEqualTo(45);
        // BaseCredit보다 감점이 크면 0
        CalculationResult floored = calculator.calculateWithPenalty(0, 2, 0, 70.0);
        assertThat(floored.finalCreditScore()).isZero();
        assertThat(floored.grade()).isEqualTo(CreditGrade.HIGH_RISK);
    }
}
