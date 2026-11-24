package com.bts.fairticket.admin.dto;

import com.bts.fairticket.credit.entity.CreditCalculation;

/**
 * Credit 산출 근거 1건 (#67). 계산 당시 실제로 쓴 값이며 조회할 때 다시 계산하지 않는다.
 * <pre>
 *   baseCredit - dynamicPenalty = rawCredit → 0~100으로 제한하고 반올림 → scoreAfter
 *   dynamicPenalty = min(70, aiPenalty + rulePenalty),  rulePenalty = min(30, rulePenaltyRaw)
 *   rulePenaltyRaw = cardPenalty + ipPenalty + variancePenalty
 * </pre>
 * kind가 ATTENDANCE(관람 이력 변경)이면 감점을 새로 계산하지 않고 저장된 dynamicPenalty를 재사용했으므로
 * 감점 상세와 룰 입력값은 null이다. 그 감점을 계산한 평가는 sourceCalculationId가 가리킨다(알 수 없으면 null).
 */
public record CreditCalculationResponse(Long calculationId, Long historyId, String kind, Long assessmentId,
        Long sourceCalculationId, Integer completedCount, Integer noShowCount, Integer autoCancelledCount,
        Double anomalyScore, Integer recentIpAttemptCount, Double intervalVariance, Integer cardSharedAccounts,
        Double baseCredit, Double aiPenalty, Double cardPenalty, Double ipPenalty, Double variancePenalty,
        Double rulePenaltyRaw, Double rulePenalty, Double dynamicPenalty, Double rawCredit,
        Integer scoreBefore, Integer scoreAfter, String tier, String reason, String createdAt) {

    public static CreditCalculationResponse from(CreditCalculation c) {
        return c == null ? null : new CreditCalculationResponse(c.getCalculationId(), c.getHistoryId(), c.getKind(),
                c.getAssessmentId(), c.getSourceCalculationId(), c.getCompletedCount(), c.getNoShowCount(),
                c.getAutoCancelledCount(), c.getAnomalyScore(), c.getRecentIpAttemptCount(), c.getIntervalVariance(),
                c.getCardSharedAccounts(), c.getBaseCredit(), c.getAiPenalty(), c.getCardPenalty(), c.getIpPenalty(),
                c.getVariancePenalty(), c.getRulePenaltyRaw(), c.getRulePenalty(), c.getDynamicPenalty(),
                c.getRawCredit(), c.getScoreBefore(), c.getScoreAfter(), c.getTier(), c.getReason(),
                AssessmentAttemptResponse.utc(c.getCreatedAt()));
    }
}
