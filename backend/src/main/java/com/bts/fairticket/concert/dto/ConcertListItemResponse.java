package com.bts.fairticket.concert.dto;

import java.time.OffsetDateTime;

/**
 * 관리자 공연 목록의 한 행 (#67). 기존 공연 필드에 anomalyUserCount를 더했다.
 * anomalyUserCount: 그 공연에 예매를 시도한 사용자 중 최신 AI 이상 점수가 0.5 이상인 고유 사용자 수.
 * AI 평가는 사용자 단위라 "이 공연에서 탐지된 건수"가 아니다. 미평가 사용자는 세지 않는다.
 */
public record ConcertListItemResponse(String serviceId, String concertId, String name,
        OffsetDateTime startsAt, OffsetDateTime endsAt, String venue, long anomalyUserCount) {

    public static ConcertListItemResponse from(ConcertResponse concert, long anomalyUserCount) {
        return new ConcertListItemResponse(concert.serviceId(), concert.concertId(), concert.name(),
                concert.startsAt(), concert.endsAt(), concert.venue(), anomalyUserCount);
    }
}
