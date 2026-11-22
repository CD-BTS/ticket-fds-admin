package com.bts.fairticket.credit.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.query.QueryPage;
import com.bts.fairticket.common.response.PageResponse;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.concert.dto.ConcertResponse;
import com.bts.fairticket.concert.repository.ConcertRepository;
import com.bts.fairticket.credit.dto.BookingHistoryQuery;
import com.bts.fairticket.credit.dto.BookingHistoryResponse;
import com.bts.fairticket.credit.entity.BookingHistory;
import com.bts.fairticket.credit.entity.BookingHistory.BookingStatus;
import com.bts.fairticket.user.repository.UserAccountRepository;

@Repository
public class BookingHistoryQueryRepository {
	private final EntityManager em;
	private final UserAccountRepository users;
	private final ConcertRepository concerts;

	public BookingHistoryQueryRepository(EntityManager em, UserAccountRepository users, ConcertRepository concerts) {
		this.em = em; this.users = users; this.concerts = concerts;
	}

	public PageResponse<BookingHistoryResponse> find(String userId, String serviceId, BookingHistoryQuery filter) {
		var page = QueryPage.parse(filter.page(), filter.size(), filter.sort(),
				Map.of("performanceStartsAt", "b.eventAt", "bookedAt", "b.bookedAt", "historyId", "b.bookingId"),
				"performanceStartsAt", "b.bookingId");
		// 다른 서비스의 사용자는 없는 사용자와 같은 응답으로 숨긴다.
		var user = users.findById(userId).filter(account -> account.getServiceId().equals(serviceId))
				.orElseThrow(() -> new ApiException(ResponseCode.USER_NOT_FOUND));
		String where = " where b.user.userId = :userId" + (filter.status() == null ? "" : " and b.status = :status");
		var count = em.createQuery("select count(b) from BookingHistory b" + where, Long.class);
		var rows = em.createQuery("select b from BookingHistory b" + where + " order by " + page.orderBy(),
				BookingHistory.class);
		for (var query : List.of(count, rows)) {
			query.setParameter("userId", user.getUserId());
			if (filter.status() != null) query.setParameter("status", BookingStatus.valueOf(filter.status()));
		}
		long total = count.getSingleResult();
		Map<String, Optional<ConcertResponse>> concertCache = new HashMap<>();
		var content = rows.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList().stream()
				.map(history -> BookingHistoryResponse.from(history, history.getEventId() == null ? null
						: concertCache.computeIfAbsent(history.getEventId(),
								concertId -> concerts.find(serviceId, concertId)).orElse(null)))
				.toList();
		return PageResponse.of(content, page.page(), page.size(), total);
	}
}
