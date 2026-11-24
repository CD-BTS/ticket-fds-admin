package com.bts.fairticket.risk.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.bts.fairticket.risk.client.AiInferenceClient.AiUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** AI 평가 주기 실행 (#40). bts.ai.enabled=true일 때만 등록된다 (기본 false: AI 서버 없이도 기동). */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bts.ai.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RiskAssessmentScheduler {

	private final RiskAssessmentService service;

	@Scheduled(initialDelayString = "${bts.ai.poll-ms:60000}", fixedDelayString = "${bts.ai.poll-ms:60000}")
	public void run() {
		try {
			service.runOnce();
		} catch (AiUnavailableException exception) {
			log.warn("AI server unavailable; will retry next run: {}", exception.getMessage());
		} catch (RuntimeException exception) {
			log.error("AI risk assessment run failed", exception);
		}
	}
}
