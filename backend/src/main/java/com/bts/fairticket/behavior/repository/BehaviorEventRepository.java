package com.bts.fairticket.behavior.repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bts.fairticket.behavior.entity.BehaviorEvent;

public interface BehaviorEventRepository extends JpaRepository<BehaviorEvent, Long> {
	Optional<BehaviorEvent> findByServiceIdAndExternalEventId(String serviceId, String externalEventId);

	// 최근 30일 이벤트 조회 (occurredAt 오름차순, 동일 시각이면 eventId 오름차순)
	List<BehaviorEvent> findByUser_UserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanEqualOrderByOccurredAtAscEventIdAsc(
			String userId, LocalDateTime start, LocalDateTime end);

	// 같은 서비스에서 기간 내 동일 결제수단(metadata.paymentIdentifier)으로 예매한 고유 계정 수 (card_reuse_count)
	@Query(value = """
			select count(distinct e.user_id) from behavior_event e
			where e.service_id = :serviceId
			  and e.event_type = 'BOOKING'
			  and e.occurred_at between cast(:start as datetime(3)) and cast(:end as datetime(3))
			  and json_unquote(json_extract(e.metadata, '$.paymentIdentifier')) = :paymentIdentifier
			""", nativeQuery = true)
	long countDistinctUsersByPaymentIdentifierText(@Param("serviceId") String serviceId,
			@Param("paymentIdentifier") String paymentIdentifier,
			@Param("start") String start, @Param("end") String end);

	default long countDistinctUsersByPaymentIdentifier(String serviceId, String paymentIdentifier,
			LocalDateTime startUtc, LocalDateTime endUtc) {
		return countDistinctUsersByPaymentIdentifierText(serviceId, paymentIdentifier,
				UTC_TEXT.format(startUtc), UTC_TEXT.format(endUtc));
	}

	// 같은 서비스에서 기간 내 동일 IP(metadata.ipAddress)로 예매한 고유 계정 수 (ip_booking_density)
	@Query(value = """
			select count(distinct e.user_id) from behavior_event e
			where e.service_id = :serviceId
			  and e.event_type = 'BOOKING'
			  and e.occurred_at between cast(:start as datetime(3)) and cast(:end as datetime(3))
			  and json_unquote(json_extract(e.metadata, '$.ipAddress')) = :ipAddress
			""", nativeQuery = true)
	long countDistinctUsersByIpAddressText(@Param("serviceId") String serviceId,
			@Param("ipAddress") String ipAddress,
			@Param("start") String start, @Param("end") String end);

	default long countDistinctUsersByIpAddress(String serviceId, String ipAddress,
			LocalDateTime startUtc, LocalDateTime endUtc) {
		return countDistinctUsersByIpAddressText(serviceId, ipAddress, UTC_TEXT.format(startUtc), UTC_TEXT.format(endUtc));
	}

	/**
	 * Credit 룰 감점 입력값(recentIpAttemptCount): 이 사용자가 구간 안에 예매 시도에 쓴 IP마다, 같은 서비스에서
	 * 같은 IP로 들어온 고유 예매 시도(attemptId) 수를 세어 최댓값을 돌려준다 (#63).
	 * BOOKING_ATTEMPT 이벤트의 metadata.ipAddress만 본다. 한 시도의 단계 이벤트가 여러 건이어도 1건이다.
	 * 구간은 start 초과 end 이하. IP가 실린 시도 이벤트가 없으면 null(감점 판단 안 함)이다.
	 * AI Feature인 ip_booking_density(30일, 예매 완료, 고유 계정 수)와는 다른 값이다.
	 */
	@Query(value = """
			select max(t.attempts) from (
				select count(distinct json_unquote(json_extract(e.metadata, '$.attemptId'))) as attempts
				from behavior_event e
				join (
					select distinct u.service_id, json_unquote(json_extract(u.metadata, '$.ipAddress')) as ip
					from behavior_event u
					where u.user_id = :userId
					  and u.event_type = 'BOOKING_ATTEMPT'
					  and u.occurred_at > cast(:start as datetime(3)) and u.occurred_at <= cast(:end as datetime(3))
					  and json_type(json_extract(u.metadata, '$.ipAddress')) = 'STRING'
					  and trim(json_unquote(json_extract(u.metadata, '$.ipAddress'))) <> ''
				) mine on e.service_id = mine.service_id
					and json_unquote(json_extract(e.metadata, '$.ipAddress')) = mine.ip
				where e.event_type = 'BOOKING_ATTEMPT'
				  and e.occurred_at > cast(:start as datetime(3)) and e.occurred_at <= cast(:end as datetime(3))
				group by mine.service_id, mine.ip
			) t
			""", nativeQuery = true)
	Long maxRecentAttemptsFromUserIpsText(@Param("userId") String userId,
			@Param("start") String start, @Param("end") String end);

	default Integer maxRecentAttemptsFromUserIps(String userId, LocalDateTime startUtc, LocalDateTime endUtc) {
		Long attempts = maxRecentAttemptsFromUserIpsText(userId, UTC_TEXT.format(startUtc), UTC_TEXT.format(endUtc));
		return attempts == null ? null : Math.toIntExact(attempts);
	}

	// 네이티브 쿼리에 LocalDateTime을 그대로 바인딩하면 JVM 시간대 기준으로 변환되어 UTC 집계 구간이 어긋난다(#63).
	// occurred_at(UTC, datetime(3))과 같은 형식의 문자열로 넘겨 DB에서 그대로 비교한다.
	DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
}