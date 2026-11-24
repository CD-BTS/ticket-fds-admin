package com.bts.fairticket.credit.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Credit 산출 근거 (#67). Credit을 계산할 때마다 1행을 남긴다(점수가 그대로여도 남긴다).
 * 현재 점수(user_credit), 점수 변경 이력(credit_history)과 구분되는 "어떻게 그 점수가 나왔는지"의 기록이다.
 * <ul>
 *   <li>EVALUATION: AI 감점과 룰 감점을 새로 계산한 평가. 입력값과 감점 상세를 모두 담는다.</li>
 *   <li>ATTENDANCE: 관람 이력 변경. 저장된 동적 감점을 재사용했으므로 감점 상세는 null이고,
 *       그 감점을 계산한 EVALUATION 행을 sourceCalculationId로 가리킨다(알 수 없으면 null).</li>
 * </ul>
 * 값은 계산 당시 실제로 쓴 값이다. 나중에 다시 계산해 채우지 않는다.
 */
@Entity
@Table(name = "credit_calculation")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditCalculation {

	public static final String KIND_EVALUATION = "EVALUATION";
	public static final String KIND_ATTENDANCE = "ATTENDANCE";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "calculation_id", nullable = false)
	private Long calculationId;

	@Column(name = "user_id", nullable = false, length = 50)
	private String userId;

	@Column(name = "history_id")
	private Long historyId;

	@Column(name = "kind", nullable = false, length = 20)
	private String kind;

	@Column(name = "assessment_id")
	private Long assessmentId;

	@Column(name = "source_calculation_id")
	private Long sourceCalculationId;

	@Column(name = "completed_count", nullable = false)
	private Integer completedCount;

	@Column(name = "no_show_count", nullable = false)
	private Integer noShowCount;

	@Column(name = "auto_cancelled_count", nullable = false)
	private Integer autoCancelledCount;

	@Column(name = "anomaly_score", columnDefinition = "double")
	private Double anomalyScore;

	@Column(name = "recent_ip_attempt_count")
	private Integer recentIpAttemptCount;

	@Column(name = "interval_variance", columnDefinition = "double")
	private Double intervalVariance;

	@Column(name = "card_shared_accounts")
	private Integer cardSharedAccounts;

	@Column(name = "base_credit", nullable = false, columnDefinition = "double")
	private Double baseCredit;

	@Column(name = "ai_penalty", columnDefinition = "double")
	private Double aiPenalty;

	@Column(name = "card_penalty", columnDefinition = "double")
	private Double cardPenalty;

	@Column(name = "ip_penalty", columnDefinition = "double")
	private Double ipPenalty;

	@Column(name = "variance_penalty", columnDefinition = "double")
	private Double variancePenalty;

	@Column(name = "rule_penalty_raw", columnDefinition = "double")
	private Double rulePenaltyRaw;

	@Column(name = "rule_penalty", columnDefinition = "double")
	private Double rulePenalty;

	@Column(name = "dynamic_penalty", nullable = false, columnDefinition = "double")
	private Double dynamicPenalty;

	@Column(name = "raw_credit", nullable = false, columnDefinition = "double")
	private Double rawCredit;

	@Column(name = "score_before", nullable = false)
	private Integer scoreBefore;

	@Column(name = "score_after", nullable = false)
	private Integer scoreAfter;

	@Column(name = "tier", nullable = false, length = 20)
	private String tier;

	@Column(name = "reason", nullable = false, length = 255)
	private String reason;

	@Column(name = "created_at", nullable = false, columnDefinition = "datetime", insertable = false, updatable = false)
	private LocalDateTime createdAt;
}
