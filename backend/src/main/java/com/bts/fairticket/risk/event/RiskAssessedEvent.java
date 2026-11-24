package com.bts.fairticket.risk.event;

/**
 * AI 이상도 평가가 risk_assessment에 저장됐음을 알린다 (#40). 커밋 이후 Credit 재산출이 이어진다.
 * bookingIntervalVariance는 평가에 쓴 Feature 값으로, Credit 룰 감점(요청 간격 분산)에 그대로 쓴다.
 */
public record RiskAssessedEvent(String userId, Long assessmentId, Double anomalyScore, Double bookingIntervalVariance) { }
