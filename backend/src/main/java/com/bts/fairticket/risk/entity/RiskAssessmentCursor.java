package com.bts.fairticket.risk.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 AI 평가 진행 위치 (#40). 마지막으로 평가에 반영한 behavior_event.event_id를 기록해,
 * 그 뒤로 새 이벤트가 들어온 사용자만 다시 평가한다. OK뿐 아니라 INSUFFICIENT_DATA·INVALID도 기록해
 * 데이터가 부족한 사용자를 매 주기 반복 호출하지 않는다. 늦게 도착한 이벤트도 event_id로 잡힌다.
 * 발생 시각이 분석 시각보다 뒤라 집계에서 빠졌던 이벤트는 checked_at 이후에 발생한 이벤트로 다시 잡힌다 (#63).
 */
@Entity
@Table(name = "risk_assessment_cursor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskAssessmentCursor {

	@Id
	@Column(name = "user_id", nullable = false, length = 50)
	private String userId;

	@Column(name = "last_event_id", nullable = false)
	private Long lastEventId;

	@Column(name = "last_status", nullable = false, length = 20)
	private String lastStatus;

	@Column(name = "checked_at", nullable = false, columnDefinition = "datetime")
	private LocalDateTime checkedAt;

	public RiskAssessmentCursor(String userId) {
		this.userId = userId;
	}

	public void advance(long lastEventId, String status, LocalDateTime checkedAtUtc) {
		this.lastEventId = lastEventId;
		this.lastStatus = status;
		this.checkedAt = checkedAtUtc;
	}
}
