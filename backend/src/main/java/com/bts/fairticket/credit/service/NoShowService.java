package com.bts.fairticket.credit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bts.fairticket.concert.dto.ConcertResponse;
import com.bts.fairticket.concert.repository.ConcertRepository;
import com.bts.fairticket.credit.entity.BookingHistory;
import com.bts.fairticket.credit.entity.BookingHistory.BookingStatus;
import com.bts.fairticket.credit.repository.BookingHistoryRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.user.repository.UserAccountRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 공연 종료 후 미입장 티켓을 NO_SHOW로 판정한다.
 *
 * 대상은 BOOKED 상태이고 (공연 종료 시각 + 유예 시간)이 지난 티켓뿐이다.
 * CHECK_IN은 비동기로 전송·재시도되므로 종료 직후 바로 판정하지 않고 유예 시간을 둔다.
 * 판정 뒤에 도착한 CHECK_IN은 BookingHistoryService가 COMPLETED로 정정한다.
 */
@Slf4j
@Service
public class NoShowService {

    static final String REASON_NO_SHOW = "공연 종료 후 미입장(NO_SHOW) 반영";

    private final BookingHistoryRepository bookingHistoryRepository;
    private final UserCreditRepository userCreditRepository;
    private final UserAccountRepository userAccountRepository;
    private final ConcertRepository concertRepository;
    private final CreditEvaluationService creditEvaluationService;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration grace;

    public NoShowService(BookingHistoryRepository bookingHistoryRepository,
            UserCreditRepository userCreditRepository, UserAccountRepository userAccountRepository,
            ConcertRepository concertRepository, CreditEvaluationService creditEvaluationService,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${bts.no-show.grace:PT1H}") Duration grace) {
        if (grace.isNegative()) {
            throw new IllegalArgumentException("bts.no-show.grace must not be negative");
        }
        this.bookingHistoryRepository = bookingHistoryRepository;
        this.userCreditRepository = userCreditRepository;
        this.userAccountRepository = userAccountRepository;
        this.concertRepository = concertRepository;
        this.creditEvaluationService = creditEvaluationService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.grace = grace;
    }

    /** @return 이번 실행에서 NO_SHOW로 바뀐 티켓 수 */
    public int markNoShows() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(grace);
        List<String> userIds = bookingHistoryRepository
                .findUserIdsByStatusAndEventEndsAtNotAfter(BookingStatus.BOOKED, cutoff);
        int marked = 0;
        for (String userId : userIds) {
            try {
                // 사용자별 트랜잭션: 한 사용자의 실패가 다른 사용자의 판정을 막지 않는다.
                Integer count = transaction.execute(status -> markForUser(userId, cutoff));
                marked += count == null ? 0 : count;
            } catch (RuntimeException exception) {
                log.error("NO_SHOW marking failed for user {}", userId, exception);
            }
        }
        return marked;
    }

    private int markForUser(String userId, LocalDateTime cutoff) {
        // 잠금 순서는 이벤트 등록과 같게 user_account → user_credit으로 맞춘다 (#40).
        // credit_history INSERT의 FK 검사가 user_account 행에 공유 잠금을 걸기 때문에,
        // user_credit을 먼저 잠그면 같은 사용자의 이벤트 등록과 서로 기다리게 된다.
        userAccountRepository.findByIdForUpdate(userId);
        userCreditRepository.findByIdForUpdate(userId);
        // 잠금을 얻은 뒤 다시 읽는다. 그 사이 CHECK_IN·CANCEL이 반영된 티켓은 여기서 빠진다.
        List<BookingHistory> overdue = bookingHistoryRepository
                .findByUserAndStatusAndEventEndsAtNotAfter(userId, BookingStatus.BOOKED, cutoff);
        int marked = 0;
        for (BookingHistory history : overdue) {
            if (postponed(history, cutoff)) {
                continue;
            }
            history.changeStatus(BookingStatus.NO_SHOW);
            marked++;
        }
        if (marked > 0) {
            // 한 사용자의 여러 티켓이 함께 바뀌어도 Credit은 최종 이력으로 한 번만 다시 계산한다.
            var user = userAccountRepository.findById(userId).orElseThrow();
            creditEvaluationService.applyAttendanceChange(user, null, REASON_NO_SHOW);
        }
        return marked;
    }

    // 공연 일정이 예매 이후 뒤로 바뀌었으면 이력의 일정을 맞추고, 새 종료 시각이 지나지 않았으면 판정을 미룬다.
    // 일정이 앞당겨진 경우에는 예매 당시 종료 시각을 그대로 써서 판정이 빨라지지 않게 한다.
    private boolean postponed(BookingHistory history, LocalDateTime cutoff) {
        if (history.getServiceId() == null || history.getEventId() == null) {
            return false;
        }
        Optional<ConcertResponse> concert = concertRepository.find(history.getServiceId(), history.getEventId());
        if (concert.isEmpty()) {
            return false;
        }
        LocalDateTime currentEnd = concert.get().endsAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if (!currentEnd.isAfter(history.getEventEndsAt())) {
            return false;
        }
        history.reschedule(concert.get().startsAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                currentEnd);
        return currentEnd.isAfter(cutoff);
    }
}
