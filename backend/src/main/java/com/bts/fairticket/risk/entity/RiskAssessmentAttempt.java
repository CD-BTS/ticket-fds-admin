package com.bts.fairticket.risk.entity;

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

import com.bts.fairticket.risk.dto.UserCalculatedFeatures;

/**
 * AI 평가 시도 이력 (#67). 한 번의 평가 시도가 끝날 때마다 1행을 남긴다.
 * <ul>
 *   <li>OK: 저장된 risk_assessment를 assessment_id로 가리킨다. Feature와 점수는 그 행에만 있다(중복 저장하지 않음).</li>
 *   <li>INSUFFICIENT_DATA, INVALID: 점수가 없다. AI에 보낸 Feature와 평가 불가 사유를 여기에 남긴다.</li>
 * </ul>
 * risk_assessment는 계속 "성공한 평가"만 담으므로 Credit 산출과 정책 판정의 최신 점수 조회는 영향받지 않는다.
 * AI 서버 장애와 타임아웃은 평가 상태가 아니므로 기록하지 않는다(다음 주기에 재시도).
 */
@Entity
@Table(name = "risk_assessment_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskAssessmentAttempt {

	/** 평가 시도 이력도 성공한 평가도 없는 사용자의 상태. DB에는 저장하지 않는 조회용 값이다. */
	public static final String NOT_EVALUATED = "NOT_EVALUATED";
	public static final String OK = "OK";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "attempt_id", nullable = false)
	private Long attemptId;

	@Column(name = "user_id", nullable = false, length = 50)
	private String userId;

	@Column(name = "status", nullable = false, length = 20)
	private String status;

	@Column(name = "assessment_id")
	private Long assessmentId;

	@Column(name = "reason", length = 500)
	private String reason;

	@Column(name = "model_version", length = 30)
	private String modelVersion;

	@Column(name = "booking_count")
	private Integer bookingCount;

	@Column(name = "avg_booking_interval", columnDefinition = "double")
	private Double avgBookingInterval;

	@Column(name = "booking_interval_variance", columnDefinition = "double")
	private Double bookingIntervalVariance;

	@Column(name = "cancel_rate", columnDefinition = "double")
	private Double cancelRate;

	@Column(name = "card_reuse_count")
	private Integer cardReuseCount;

	@Column(name = "ip_booking_density")
	private Integer ipBookingDensity;

	@Column(name = "calculated_at", nullable = false, columnDefinition = "datetime")
	private LocalDateTime calculatedAt;

	@Column(name = "created_at", nullable = false, columnDefinition = "datetime", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 성공한 평가의 시도. Feature는 연결된 risk_assessment에 있다. */
	public static RiskAssessmentAttempt succeeded(String userId, Long assessmentId, String modelVersion,
			LocalDateTime calculatedAtUtc) {
		RiskAssessmentAttempt attempt = new RiskAssessmentAttempt();
		attempt.userId = userId;
		attempt.status = OK;
		attempt.assessmentId = assessmentId;
		attempt.modelVersion = modelVersion;
		attempt.calculatedAt = calculatedAtUtc;
		return attempt;
	}

	/** 점수 없이 끝난 시도(INSUFFICIENT_DATA, INVALID). AI에 보낸 Feature를 그대로 남긴다. */
	public static RiskAssessmentAttempt notScored(String userId, String status, String reason, String modelVersion,
			UserCalculatedFeatures sent, LocalDateTime calculatedAtUtc) {
		RiskAssessmentAttempt attempt = new RiskAssessmentAttempt();
		attempt.userId = userId;
		attempt.status = status;
		attempt.reason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
		attempt.modelVersion = modelVersion == null || modelVersion.isBlank() || modelVersion.length() > 30
				? null : modelVersion;
		attempt.bookingCount = sent.getBookingCount();
		attempt.avgBookingInterval = sent.getAvgBookingInterval();
		attempt.bookingIntervalVariance = sent.getBookingIntervalVariance();
		attempt.cancelRate = sent.getCancelRate();
		attempt.cardReuseCount = sent.getCardReuseCount();
		attempt.ipBookingDensity = sent.getIpBookingDensity();
		attempt.calculatedAt = calculatedAtUtc;
		return attempt;
	}
}
