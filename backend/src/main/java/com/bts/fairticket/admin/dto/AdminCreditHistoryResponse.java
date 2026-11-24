package com.bts.fairticket.admin.dto;

import com.bts.fairticket.credit.entity.CreditCalculation;
import com.bts.fairticket.credit.entity.CreditHistory;

/**
 * 관리자용 Credit 변경 이력 1건 (#67). createdAt은 UTC(Z)다.
 * calculation은 그 변경의 산출 근거이며, #67 이전에 기록된 이력은 null이다(과거 이력을 다시 계산해 채우지 않는다).
 */
public record AdminCreditHistoryResponse(Long historyId, Long eventId, Integer scoreBefore, Integer scoreAfter,
        String reason, String createdAt, CreditCalculationResponse calculation) {

    public static AdminCreditHistoryResponse from(CreditHistory history, CreditCalculation calculation) {
        return new AdminCreditHistoryResponse(history.getHistoryId(),
                history.getEvent() == null ? null : history.getEvent().getEventId(),
                history.getScoreBefore(), history.getScoreAfter(), history.getReason(),
                AssessmentAttemptResponse.utc(history.getCreatedAt()), CreditCalculationResponse.from(calculation));
    }
}
