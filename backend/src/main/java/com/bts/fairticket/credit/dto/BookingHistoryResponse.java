package com.bts.fairticket.credit.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.bts.fairticket.concert.dto.ConcertResponse;
import com.bts.fairticket.credit.entity.BookingHistory;

/** 티켓 1장의 관람 이력. 시각은 모두 UTC ISO 8601 문자열이다. */
public record BookingHistoryResponse(
		Long historyId,
		String serviceId,
		String bookingId,
		String ticketId,
		String concertId,
		String concertName,
		String venue,
		String status,
		String bookedAt,
		String performanceStartsAt,
		String performanceEndsAt,
		String createdAt) {

	// concert는 등록된 공연 정보이며 없으면 null이다. 공연 시각은 이력에 저장된 값을 쓴다.
	public static BookingHistoryResponse from(BookingHistory history, ConcertResponse concert) {
		return new BookingHistoryResponse(history.getBookingId(), history.getServiceId(),
				history.getBookingReference(), history.getTicketId(), history.getEventId(),
				concert == null ? null : concert.name(), concert == null ? null : concert.venue(),
				history.getStatus().name(), utc(history.getBookedAt()), utc(history.getEventAt()),
				utc(history.getEventEndsAt()), utc(history.getCreatedAt()));
	}

	private static String utc(LocalDateTime time) {
		return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
	}
}
