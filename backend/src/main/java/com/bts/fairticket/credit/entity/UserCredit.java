package com.bts.fairticket.credit.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import com.bts.fairticket.user.entity.UserAccount;

@Entity
@Table(name = "user_credit",
		indexes = {
				@Index(name = "user_credit_index_8", columnList = "tier")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCredit {

	@Id
	@Column(name = "user_id", nullable = false, length = 50)
	private String userId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.PERSIST, optional = false)
	@JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false)
	private UserAccount user;

	// 신규 사용자 초기 Credit은 정책 값 50이다. 이력이 없을 때의 BaseCredit과 같다.
	@ColumnDefault("50")
	@Column(name = "credit_score", nullable = false)
	private Integer creditScore = 50;

	// 마지막 평가에서 적용한 DynamicPenalty(AI + Rule). NULL이면 저장된 감점 정보가 없다(#56 이전 행).
	@Column(name = "dynamic_penalty", nullable = true, columnDefinition = "double")
	private Double dynamicPenalty;

	@ColumnDefault("'BASE'")
	@Column(name = "tier", nullable = false, length = 20)
	private String tier = "BASE";

	@Generated(event = EventType.INSERT)
	@ColumnDefault("(now())")
	@Column(name = "updated_at", nullable = false, columnDefinition = "datetime", insertable = false, updatable = true)
	private LocalDateTime updatedAt;

	public UserCredit(UserAccount user) {
		this.user = user;
	}

	public void updateCreditScore(int newScore, String tier) {
    this.creditScore = newScore;
    this.tier = tier;
    // updatedAt 필드가 있다면 LocalDateTime.now() 갱신
}

	// 평가에 쓴 DynamicPenalty를 함께 저장한다. 관람 이력 자동 갱신이 같은 감점으로 다시 계산한다.
	public void updateCreditScore(int newScore, String tier, double dynamicPenalty) {
		updateCreditScore(newScore, tier);
		this.dynamicPenalty = dynamicPenalty;
	}
}
