package com.bts.fairticket.credit.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.bts.fairticket.user.entity.UserAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "booking_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_booking_service_ticket",
                columnNames = {"service_id", "ticket_id"}),
        indexes = {
        @Index(name = "idx_booking_user_status", columnList = "user_id, status"),
        @Index(name = "idx_booking_user_event", columnList = "user_id, event_at"),
        @Index(name = "idx_booking_status_ends", columnList = "status, event_ends_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    // 외부 서비스 식별자와 티켓 ID. 관람 이력은 티켓 1장당 1행이다(#56). 이전에 적재된 행은 NULL.
    @Column(name = "service_id", length = 50)
    private String serviceId;

    @Column(name = "ticket_id", length = 100)
    private String ticketId;

    @Column(name = "booking_reference", nullable = false, length = 100)
    private String bookingReference;

    @Column(name = "event_id", length = 50)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "booked_at", nullable = false)
    private LocalDateTime bookedAt;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;

    // 공연 종료 시각(UTC). NO_SHOW 판정 기준이며, 모르면 NULL이고 자동 판정하지 않는다.
    @Column(name = "event_ends_at")
    private LocalDateTime eventEndsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    public BookingHistory(UserAccount user, String bookingReference, String eventId,
                          BookingStatus status, LocalDateTime bookedAt, LocalDateTime eventAt) {
        this.user = user;
        this.bookingReference = bookingReference;
        this.eventId = eventId;
        this.status = status;
        this.bookedAt = bookedAt;
        this.eventAt = eventAt;
    }

    // 이벤트 기반 티켓 단위 이력 생성
    public BookingHistory(UserAccount user, String serviceId, String ticketId, String bookingReference,
                          String eventId, BookingStatus status, LocalDateTime bookedAt,
                          LocalDateTime eventAt, LocalDateTime eventEndsAt) {
        this(user, bookingReference, eventId, status, bookedAt, eventAt);
        this.serviceId = serviceId;
        this.ticketId = ticketId;
        this.eventEndsAt = eventEndsAt;
    }

    public void changeStatus(BookingStatus status) {
        this.status = status;
    }

    // CHECK_IN·CANCEL이 먼저 도착해 만들어진 행에 BOOKING의 예매 정보를 채운다. 상태는 바꾸지 않는다.
    public void fillBookingInfo(String bookingReference, String eventId, LocalDateTime bookedAt,
                                LocalDateTime eventAt, LocalDateTime eventEndsAt) {
        this.bookingReference = bookingReference;
        this.bookedAt = bookedAt;
        this.eventAt = eventAt;
        if (eventId != null) {
            this.eventId = eventId;
        }
        if (eventEndsAt != null) {
            this.eventEndsAt = eventEndsAt;
        }
    }

    // 공연 일정이 예매 이후 뒤로 바뀐 경우 현재 일정으로 맞춘다.
    public void reschedule(LocalDateTime eventAt, LocalDateTime eventEndsAt) {
        this.eventAt = eventAt;
        this.eventEndsAt = eventEndsAt;
    }

    public enum BookingStatus {
        BOOKED, COMPLETED, NO_SHOW, AUTO_CANCELLED, CANCELLED
    }
}