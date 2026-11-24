package com.bts.fairticket.risk.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.dto.CreditEvaluationContext;
import com.bts.fairticket.credit.service.CreditEvaluationService;
import com.bts.fairticket.risk.event.RiskAssessedEvent;

class RiskAssessedCreditListenerTest {

	@Test
	void reEvaluatesCreditInNewTransactionWithFeatureVarianceAndRecentIpAttempts() {
		var credits = mock(CreditEvaluationService.class);
		var tx = mock(PlatformTransactionManager.class);
		new RiskAssessedCreditListener(credits, tx).onAssessed(new RiskAssessedEvent("u1", 5L, 0.72, 0.4, 7));

		var context = ArgumentCaptor.forClass(CreditEvaluationContext.class);
		verify(credits).evaluateAndApplyCredit(eq("u1"), context.capture());
		assertEquals(RiskAssessedCreditListener.TRIGGER_REASON, context.getValue().triggerReason());
		assertEquals(7, context.getValue().recentIpAttemptCount());
		assertEquals(0.4, context.getValue().intervalVariance(), 1e-9);

		var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(tx).getTransaction(definition.capture());
		assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
	}

	@Test
	void failureIsLoggedNotThrown() {
		var credits = mock(CreditEvaluationService.class);
		when(credits.evaluateAndApplyCredit(any(), any())).thenThrow(new ApiException(ResponseCode.CREDIT_NOT_FOUND));
		var listener = new RiskAssessedCreditListener(credits, mock(PlatformTransactionManager.class));
		assertDoesNotThrow(() -> listener.onAssessed(new RiskAssessedEvent("u1", 5L, 0.72, null, null)));
	}
}
