package com.bts.fairticket.risk.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.bts.fairticket.credit.dto.CreditEvaluationContext;
import com.bts.fairticket.credit.service.CreditEvaluationService;
import com.bts.fairticket.risk.event.RiskAssessedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 평가가 커밋되면 Credit을 다시 계산하고 credit_history에 남긴다 (#40 3단계).
 * AFTER_COMMIT 단계에서는 원래 트랜잭션 자원이 아직 묶여 있어 일반 @Transactional로는 쓰기가 커밋되지 않으므로
 * REQUIRES_NEW 트랜잭션을 따로 연다. 실패해도 평가 저장은 되돌리지 않고, 다음 평가 때 다시 반영된다.
 */
@Slf4j
@Component
public class RiskAssessedCreditListener {

	static final String TRIGGER_REASON = "AI 이상도 평가 반영";

	private final CreditEvaluationService credits;
	private final TransactionTemplate newTransaction;

	public RiskAssessedCreditListener(CreditEvaluationService credits, PlatformTransactionManager transactions) {
		this.credits = credits;
		this.newTransaction = new TransactionTemplate(transactions);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAssessed(RiskAssessedEvent event) {
		try {
			// 최근 10분 IP 시도 수는 이 경로에서 알 수 없어 null(감점 없음)로 둔다. 간격 분산은 평가에 쓴 Feature 값을 쓴다.
			var context = new CreditEvaluationContext(TRIGGER_REASON, null, event.bookingIntervalVariance());
			newTransaction.executeWithoutResult(tx -> credits.evaluateAndApplyCredit(event.userId(), context));
		} catch (RuntimeException exception) {
			log.warn("Credit re-evaluation after AI assessment failed (user={}, assessmentId={}): {}",
					event.userId(), event.assessmentId(), exception.toString());
		}
	}
}
