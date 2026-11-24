package com.bts.fairticket.concert.dto;

import jakarta.validation.constraints.Size;

/** name(#67): 공연 이름 부분 검색. 대소문자를 구분하지 않는다. */
public record ConcertQuery(Integer page, Integer size, String sort, @Size(min = 1, max = 200) String name) { }
