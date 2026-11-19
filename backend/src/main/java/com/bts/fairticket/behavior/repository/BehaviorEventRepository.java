package com.bts.fairticket.behavior.repository;

import java.time.LocalDateTime;
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
			  and e.occurred_at between :start and :end
			  and json_unquote(json_extract(e.metadata, '$.paymentIdentifier')) = :paymentIdentifier
			""", nativeQuery = true)
	long countDistinctUsersByPaymentIdentifier(@Param("serviceId") String serviceId,
			@Param("paymentIdentifier") String paymentIdentifier,
			@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

	// 같은 서비스에서 기간 내 동일 IP(metadata.ipAddress)로 예매한 고유 계정 수 (ip_booking_density)
	@Query(value = """
			select count(distinct e.user_id) from behavior_event e
			where e.service_id = :serviceId
			  and e.event_type = 'BOOKING'
			  and e.occurred_at between :start and :end
			  and json_unquote(json_extract(e.metadata, '$.ipAddress')) = :ipAddress
			""", nativeQuery = true)
	long countDistinctUsersByIpAddress(@Param("serviceId") String serviceId,
			@Param("ipAddress") String ipAddress,
			@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}