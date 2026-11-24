package com.bts.fairticket.policy.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 티켓팅 서비스가 실제로 적용한 정책 1건 (#67). BTS가 정책을 "결정"한 기록(policy_decision)과 구분한다.
 * behavior_event와 분리되어 있어 AI 평가 대상 선정, Feature, Credit 산출에 쓰이지 않는다.
 */
@Entity
@Table(name = "policy_application")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyApplication {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "application_id", nullable = false)
	private Long applicationId;

	@Column(name = "service_id", nullable = false, length = 50)
	private String serviceId;

	@Column(name = "external_application_id", nullable = false, length = 100)
	private String externalApplicationId;

	@Column(name = "user_id", nullable = false, length = 50)
	private String userId;

	@Column(name = "decision_id", nullable = false)
	private Long decisionId;

	@Column(name = "concert_id", nullable = false, length = 50)
	private String concertId;

	@Column(name = "action", nullable = false, length = 30)
	private String action;

	@Column(name = "result", nullable = false, length = 20)
	private String result;

	@Column(name = "applied_at", nullable = false, columnDefinition = "datetime(3)")
	private LocalDateTime appliedAt;

	@Column(name = "created_at", nullable = false, columnDefinition = "datetime", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	public PolicyApplication(String serviceId, String externalApplicationId, String userId, Long decisionId,
			String concertId, String action, String result, LocalDateTime appliedAtUtc) {
		this.serviceId = serviceId;
		this.externalApplicationId = externalApplicationId;
		this.userId = userId;
		this.decisionId = decisionId;
		this.concertId = concertId;
		this.action = action;
		this.result = result;
		this.appliedAt = appliedAtUtc;
	}
}
