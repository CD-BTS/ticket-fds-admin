package com.bts.fairticket.credit.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.bts.fairticket.user.entity.UserAccount;

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

@Entity
@Table(name = "user_card", indexes = {
        @Index(name = "idx_user_card_hash", columnList = "card_hash"),
        @Index(name = "idx_user_card_user", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "card_hash", nullable = false, length = 64)
    private String cardHash;

    @Column(name = "card_company", length = 30)
    private String cardCompany;

    @Column(name = "masked_card_no", length = 20)
    private String maskedCardNo;

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    /** 같은 카드를 다시 썼을 때 더 최근 시각으로만 갱신한다. 늦게 도착한 과거 이벤트로는 되돌아가지 않는다. */
    public void markUsed(LocalDateTime usedAtUtc) {
        if (usedAtUtc != null && usedAtUtc.isAfter(this.lastUsedAt)) {
            this.lastUsedAt = usedAtUtc;
        }
    }

    /** 예매 이벤트에서 처음 본 카드. 마지막 사용 시각은 그 예매가 발생한 시각(UTC)이다 (#63). */
    public UserCard(UserAccount user, String cardHash, LocalDateTime usedAtUtc) {
        this(user, cardHash, null, null);
        if (usedAtUtc != null) {
            this.lastUsedAt = usedAtUtc;
        }
    }

    public UserCard(UserAccount user, String cardHash, String cardCompany, String maskedCardNo) {
        this.user = user;
        this.cardHash = cardHash;
        this.cardCompany = cardCompany;
        this.maskedCardNo = maskedCardNo;
    }
}