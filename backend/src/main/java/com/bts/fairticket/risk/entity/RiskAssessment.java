package com.bts.fairticket.risk.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import com.bts.fairticket.user.entity.UserAccount;

@Entity
@Table(name = "risk_assessment",
		indexes = {
				@Index(name = "risk_assessment_index_11", columnList = "user_id,assessed_at"),
				@Index(name = "risk_assessment_index_12", columnList = "assessed_at")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskAssessment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "assessment_id", nullable = false)
	private Long assessmentId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false)
	private UserAccount user;

	@Column(name = "anomaly_score", nullable = false, columnDefinition = "double")
	private Double anomalyScore;

	@Column(name = "model_version", nullable = false, length = 30)
	private String modelVersion;

	@Generated(event = EventType.INSERT)
	@ColumnDefault("(now())")
	@Column(name = "assessed_at", nullable = false, columnDefinition = "datetime", insertable = false, updatable = false)
	private LocalDateTime assessedAt;

	// AI에 실제로 보낸 Feature 6개 (#67). 저장할 때 다시 계산하지 않는다. 계산할 수 없던 값과 #67 이전 행은 null이다.
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

	public RiskAssessment(UserAccount user, Double anomalyScore, String modelVersion) {
		this.user = user;
		this.anomalyScore = anomalyScore;
		this.modelVersion = modelVersion;
	}

	/** @param sent 이 평가의 AI 요청에 넣은 Feature 값 */
	public RiskAssessment(UserAccount user, Double anomalyScore, String modelVersion,
			com.bts.fairticket.risk.dto.UserCalculatedFeatures sent) {
		this(user, anomalyScore, modelVersion);
		this.bookingCount = sent.getBookingCount();
		this.avgBookingInterval = sent.getAvgBookingInterval();
		this.bookingIntervalVariance = sent.getBookingIntervalVariance();
		this.cancelRate = sent.getCancelRate();
		this.cardReuseCount = sent.getCardReuseCount();
		this.ipBookingDensity = sent.getIpBookingDensity();
	}
}
