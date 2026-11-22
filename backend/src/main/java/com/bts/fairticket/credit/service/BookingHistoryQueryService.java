package com.bts.fairticket.credit.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.common.response.PageResponse;
import com.bts.fairticket.credit.dto.BookingHistoryQuery;
import com.bts.fairticket.credit.dto.BookingHistoryResponse;
import com.bts.fairticket.credit.repository.BookingHistoryQueryRepository;

@Service
public class BookingHistoryQueryService {
	private final BookingHistoryQueryRepository repository;
	public BookingHistoryQueryService(BookingHistoryQueryRepository repository) { this.repository = repository; }

	@Transactional(readOnly = true)
	public PageResponse<BookingHistoryResponse> find(String userId, String serviceId, BookingHistoryQuery query) {
		return repository.find(userId, serviceId, query);
	}
}
