package com.bts.fairticket.credit.dto;

import jakarta.validation.constraints.Pattern;

public record BookingHistoryQuery(Integer page, Integer size, String sort,
		@Pattern(regexp = "BOOKED|COMPLETED|NO_SHOW|AUTO_CANCELLED|CANCELLED") String status) { }
