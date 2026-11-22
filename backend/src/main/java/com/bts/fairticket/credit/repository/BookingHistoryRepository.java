package com.bts.fairticket.credit.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bts.fairticket.credit.entity.BookingHistory;
import com.bts.fairticket.credit.entity.BookingHistory.BookingStatus;

public interface BookingHistoryRepository extends JpaRepository<BookingHistory, Long> {

    long countByUser_UserIdAndStatus(String userId, BookingStatus status);

    Optional<BookingHistory> findByServiceIdAndTicketId(String serviceId, String ticketId);

    List<BookingHistory> findByUser_UserIdAndServiceIdAndBookingReference(
            String userId, String serviceId, String bookingReference);

    // NO_SHOW 판정 후보: 종료 시각이 기준 시각 이전인데 아직 BOOKED인 티켓을 가진 사용자
    @Query("""
        SELECT DISTINCT b.user.userId FROM BookingHistory b
        WHERE b.status = :status AND b.eventEndsAt IS NOT NULL AND b.eventEndsAt <= :cutoff
    """)
    List<String> findUserIdsByStatusAndEventEndsAtNotAfter(
            @Param("status") BookingStatus status, @Param("cutoff") LocalDateTime cutoff);

    @Query("""
        SELECT b FROM BookingHistory b
        WHERE b.user.userId = :userId AND b.status = :status
          AND b.eventEndsAt IS NOT NULL AND b.eventEndsAt <= :cutoff
        ORDER BY b.bookingId
    """)
    List<BookingHistory> findByUserAndStatusAndEventEndsAtNotAfter(@Param("userId") String userId,
            @Param("status") BookingStatus status, @Param("cutoff") LocalDateTime cutoff);

    // 관람 이력 한 번에 카운트하는 프로젝션 인터페이스
    @Query("""
        SELECT 
            SUM(CASE WHEN b.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completedCount,
            SUM(CASE WHEN b.status = 'NO_SHOW' THEN 1 ELSE 0 END) AS noShowCount,
            SUM(CASE WHEN b.status = 'AUTO_CANCELLED' THEN 1 ELSE 0 END) AS autoCancelledCount
        FROM BookingHistory b
        WHERE b.user.userId = :userId
    """)
    BookingStats getBookingStatsByUserId(@Param("userId") String userId);

    interface BookingStats {
        Long getCompletedCount();
        Long getNoShowCount();
        Long getAutoCancelledCount();
    }
}