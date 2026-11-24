package com.bts.fairticket.credit.policy;

import org.springframework.stereotype.Component;

@Component
public class CreditCalculator {

    public record CalculationInput(
            long completedCount,       // A: 정상 관람 완료 횟수
            long noShowCount,          // N: 노쇼 횟수
            long autoCancelledCount,   // C_auto: 미입금 자동 취소 횟수
            Double anomalyScore,       // S_anomaly: Isolation Forest 이상도 (0.0 ~ 1.0, 0.5 이상이 탐지, null 가능)
            double rulePenalty         // 룰 기반 감점 합산 (최대 30점)
    ) {}

    public record CalculationResult(
            double baseCredit,
            double dynamicPenalty,
            int finalCreditScore,
            CreditGrade grade,
            String penaltyPolicy
    ) {}

    public enum CreditGrade {
        NORMAL, SUSPICIOUS, HIGH_RISK
    }

    /**
     * 관람 이력, AI 이상도, 룰 감점을 종합하여 최종 Credit 및 등급 산출
     */
    public CalculationResult calculate(CalculationInput input) {
        // 1. BaseCredit 산출 (베이지안 평활화)
        double baseCredit = baseCredit(input.completedCount(), input.noShowCount(), input.autoCancelledCount());

        // 2. DynamicPenalty 산출
        double anomalyScore = (input.anomalyScore() != null) ? Math.max(0.0, Math.min(1.0, input.anomalyScore())) : 0.0;
        // AI 감점은 탐지 기준(anomalyScore 0.5) 이상에서만 적용한다: 0.5 → 0점, 1.0 → 40점 (#60).
        // 0.5 미만은 모델이 이상으로 판정하지 않은 구간이므로 감점하지 않는다.
        double aiPenalty = CreditPolicyConstants.WEIGHT_ANOMALY_SCORE * Math.max(0.0, 2.0 * anomalyScore - 1.0);
        double rulePenalty = Math.min(CreditPolicyConstants.MAX_RULE_PENALTY, Math.max(0.0, input.rulePenalty()));

        double dynamicPenalty = Math.min(CreditPolicyConstants.MAX_TOTAL_PENALTY, aiPenalty + rulePenalty);

        return finalResult(baseCredit, dynamicPenalty);
    }

    /**
     * 저장된 DynamicPenalty를 그대로 적용해 최종 Credit을 산출한다 (관람 이력 자동 갱신용).
     * BaseCredit 식과 최종 점수·등급 판정은 calculate와 같은 코드를 쓰므로,
     * 같은 관람 이력과 같은 감점이면 수동 평가와 결과가 같다.
     */
    public CalculationResult calculateWithPenalty(long completedCount, long noShowCount,
            long autoCancelledCount, double dynamicPenalty) {
        double penalty = Math.min(CreditPolicyConstants.MAX_TOTAL_PENALTY, Math.max(0.0, dynamicPenalty));
        return finalResult(baseCredit(completedCount, noShowCount, autoCancelledCount), penalty);
    }

    private double baseCredit(long completedCount, long noShowCount, long autoCancelledCount) {
        double weightedA = completedCount * CreditPolicyConstants.WEIGHT_COMPLETED;
        double weightedN = noShowCount * CreditPolicyConstants.WEIGHT_NO_SHOW;
        double weightedC = autoCancelledCount * CreditPolicyConstants.WEIGHT_AUTO_CANCEL;

        return 100.0 * (weightedA + CreditPolicyConstants.BAYESIAN_ALPHA)
                / (weightedA + weightedN + weightedC + CreditPolicyConstants.BAYESIAN_TOTAL_SMOOTHING);
    }

    private CalculationResult finalResult(double baseCredit, double dynamicPenalty) {
        // 3. 최종 Credit 점수 (0 ~ 100 클램핑 및 반올림)
        int finalCredit = (int) Math.round(Math.max(0.0, Math.min(100.0, baseCredit - dynamicPenalty)));

        // 4. 등급 및 제재 정책 판정
        CreditGrade grade;
        String penaltyPolicy;
        if (finalCredit >= CreditPolicyConstants.THRESHOLD_NORMAL) {
            grade = CreditGrade.NORMAL;
            penaltyPolicy = "일반 예매 및 대기열 입장";
        } else if (finalCredit >= CreditPolicyConstants.THRESHOLD_SUSPICIOUS) {
            grade = CreditGrade.SUSPICIOUS;
            penaltyPolicy = "CAPTCHA 추가 인증";
        } else {
            grade = CreditGrade.HIGH_RISK;
            penaltyPolicy = "CAPTCHA + 대기열 후순위 배치";
        }

        return new CalculationResult(baseCredit, dynamicPenalty, finalCredit, grade, penaltyPolicy);
    }
}