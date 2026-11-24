package com.bts.fairticket.credit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.behavior.entity.BehaviorEvent;
import com.bts.fairticket.concert.dto.ConcertResponse;
import com.bts.fairticket.concert.repository.ConcertRepository;
import com.bts.fairticket.credit.entity.BookingHistory;
import com.bts.fairticket.credit.entity.BookingHistory.BookingStatus;
import com.bts.fairticket.credit.entity.UserCard;
import com.bts.fairticket.credit.repository.BookingHistoryRepository;
import com.bts.fairticket.credit.repository.UserCardRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.user.entity.UserAccount;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * BOOKING / CANCEL / CHECK_IN 이벤트를 티켓 단위 관람 이력(booking_history)에 반영한다.
 *
 * 이벤트를 저장하는 트랜잭션 안에서 호출되므로 이벤트 저장과 이력 갱신은 함께 커밋되거나 함께 롤백된다.
 * 새로 저장된 이벤트에 대해서만 호출되고(재전송은 호출되지 않음), 상태가 실제로 바뀐 경우에만
 * Credit을 다시 계산한다. 필요한 정보가 없는 이벤트는 이벤트 수집을 막지 않도록 건너뛴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingHistoryService {

    static final String REASON_CHECK_IN = "관람 완료(CHECK_IN) 반영";
    static final String REASON_LATE_CHECK_IN = "지연 도착한 CHECK_IN으로 노쇼 정정";
    static final String REASON_LATE_CANCEL = "지연 도착한 CANCEL로 노쇼 정정";

    // 결제 식별자 정규화: 앞뒤의 공백(U+0020), 탭, CR, LF. 백필 SQL의 REGEXP_REPLACE와 같은 문자 집합이다.
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$");
    private static final int MAX_TICKET_ID_LENGTH = 100;
    private static final int MAX_CONCERT_ID_LENGTH = 50;

    private final BookingHistoryRepository bookingHistoryRepository;
    private final UserCreditRepository userCreditRepository;
    private final ConcertRepository concertRepository;
    private final CreditEvaluationService creditEvaluationService;
    private final UserCardRepository userCardRepository;

    @Transactional
    public void apply(BehaviorEvent event) {
        if ("BOOKING".equals(event.getEventType())) {
            recordPaymentCard(event);
        }
        switch (event.getEventType()) {
            case "BOOKING" -> applyBooking(event);
            case "CANCEL" -> applyCancel(event);
            case "CHECK_IN" -> applyCheckIn(event);
            default -> { }
        }
    }

    /**
     * BOOKING의 결제 식별자(metadata.paymentIdentifier)를 user_card에 남긴다 (#63).
     * Credit의 카드 공유 룰 감점(같은 카드 해시를 가진 고유 계정 수)이 이 테이블을 읽는다.
     * 카드 해시에 serviceId가 들어가므로 다른 서비스의 같은 식별자와 섞이지 않는다({@link #cardHash}).
     * 같은 사용자가 같은 카드로 다시 결제하면 행을 늘리지 않고 마지막 사용 시각만 바꾼다.
     * 이벤트 등록이 사용자 단위로 직렬화되어 있어(user_account 행 잠금) 같은 사용자의 중복 행이 생기지 않는다.
     * 결제 식별자가 없으면 아무것도 하지 않는다.
     */
    private void recordPaymentCard(BehaviorEvent event) {
        String cardHash = cardHash(event.getServiceId(), event.getMetadata());
        if (cardHash == null) {
            return;
        }
        UserAccount user = event.getUser();
        LocalDateTime usedAt = seconds(event.getOccurredAt());
        List<UserCard> existing = userCardRepository.findByUser_UserIdAndCardHash(user.getUserId(), cardHash);
        if (existing.isEmpty()) {
            // 새 카드의 마지막 사용 시각은 처리 시각이 아니라 이 예매가 발생한 시각이다.
            userCardRepository.save(new UserCard(user, cardHash, usedAt));
        } else {
            existing.get(0).markUsed(usedAt);
        }
    }

    /**
     * 카드 해시: SHA-256("{serviceId의 UTF-8 바이트 수}:{serviceId}:{결제 식별자}")의 소문자 16진수 64자.
     * serviceId 길이를 앞에 붙여, serviceId나 식별자에 ':'가 들어 있어도 서로 다른 (서비스, 식별자) 조합이
     * 같은 입력 문자열이 되지 않는다. 결제 식별자는 앞뒤의 공백·탭·CR·LF만 떼어낸다.
     * db/manual/063-backfill-user-card-from-booking-events.sql이 같은 규칙으로 계산하므로 함께 바꿔야 한다.
     *
     * @return 결제 식별자가 문자열이 아니거나 떼어낸 뒤 비어 있으면 null
     */
    static String cardHash(String serviceId, Map<String, Object> metadata) {
        if (serviceId == null || metadata == null || !(metadata.get("paymentIdentifier") instanceof String raw)) {
            return null;
        }
        String identifier = EDGE_WHITESPACE.matcher(raw).replaceAll("");
        if (identifier.isEmpty()) {
            return null;
        }
        try {
            int serviceIdBytes = serviceId.getBytes(StandardCharsets.UTF_8).length;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((serviceIdBytes + ":" + serviceId + ":" + identifier).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // BOOKING: 티켓별 BOOKED 이력을 만든다. 이미 있는 티켓은 예매 정보만 채우고 상태를 되돌리지 않는다.
    private void applyBooking(BehaviorEvent event) {
        List<String> ticketIds = ticketIds(event);
        if (ticketIds.isEmpty() || event.getBookingId() == null) {
            return; // 티켓 정보가 없는 BOOKING은 관람 이력을 만들 수 없다
        }
        Schedule schedule = schedule(event);
        if (schedule.startsAt() == null) {
            log.warn("BOOKING event {} has no performance start time; booking history is not created",
                    event.getEventId());
            return;
        }
        UserAccount user = event.getUser();
        lockUser(user);
        LocalDateTime bookedAt = seconds(event.getOccurredAt());
        for (String ticketId : ticketIds) {
            Optional<BookingHistory> existing =
                    bookingHistoryRepository.findByServiceIdAndTicketId(event.getServiceId(), ticketId);
            if (existing.isEmpty()) {
                bookingHistoryRepository.save(new BookingHistory(user, event.getServiceId(), ticketId,
                        event.getBookingId(), schedule.concertId(), BookingStatus.BOOKED, bookedAt,
                        schedule.startsAt(), schedule.endsAt()));
            } else if (ownedBy(existing.get(), user, event)) {
                existing.get().fillBookingInfo(event.getBookingId(), schedule.concertId(), bookedAt,
                        schedule.startsAt(), schedule.endsAt());
            }
        }
    }

    // CANCEL: 미입장 티켓을 CANCELLED로 바꾼다. COMPLETED는 유지한다.
    private void applyCancel(BehaviorEvent event) {
        if (event.getBookingId() == null) {
            return;
        }
        UserAccount user = event.getUser();
        List<String> ticketIds = ticketIds(event);
        lockUser(user);

        List<BookingHistory> targets = new ArrayList<>();
        if (ticketIds.isEmpty()) {
            targets.addAll(bookingHistoryRepository.findByUser_UserIdAndServiceIdAndBookingReference(
                    user.getUserId(), event.getServiceId(), event.getBookingId()));
        } else {
            Schedule schedule = null;
            for (String ticketId : ticketIds) {
                Optional<BookingHistory> existing =
                        bookingHistoryRepository.findByServiceIdAndTicketId(event.getServiceId(), ticketId);
                if (existing.isPresent()) {
                    if (ownedBy(existing.get(), user, event)) {
                        targets.add(existing.get());
                    }
                    continue;
                }
                // CANCEL이 BOOKING보다 먼저 도착: 뒤늦은 BOOKING이 BOOKED로 만들지 않도록 CANCELLED 행을 남긴다.
                if (schedule == null) {
                    schedule = schedule(event);
                }
                if (schedule.startsAt() != null) {
                    bookingHistoryRepository.save(new BookingHistory(user, event.getServiceId(), ticketId,
                            event.getBookingId(), schedule.concertId(), BookingStatus.CANCELLED,
                            seconds(event.getOccurredAt()), schedule.startsAt(), schedule.endsAt()));
                }
            }
        }

        boolean noShowCorrected = false;
        for (BookingHistory history : targets) {
            if (history.getStatus() == BookingStatus.BOOKED) {
                history.changeStatus(BookingStatus.CANCELLED);
            } else if (history.getStatus() == BookingStatus.NO_SHOW) {
                // 취소는 공연 전에만 가능하다. 전송이 늦어 먼저 노쇼로 판정된 티켓을 바로잡는다.
                history.changeStatus(BookingStatus.CANCELLED);
                noShowCorrected = true;
            }
        }
        if (noShowCorrected) {
            creditEvaluationService.applyAttendanceChange(user, event, REASON_LATE_CANCEL);
        }
    }

    // CHECK_IN: 티켓을 COMPLETED로 바꾼다. BOOKING보다 먼저 도착하면 COMPLETED 행을 새로 만든다.
    private void applyCheckIn(BehaviorEvent event) {
        String ticketId = event.getTicketId();
        if (ticketId == null || ticketId.isBlank()) {
            return;
        }
        UserAccount user = event.getUser();
        lockUser(user);

        Optional<BookingHistory> existing =
                bookingHistoryRepository.findByServiceIdAndTicketId(event.getServiceId(), ticketId);
        if (existing.isPresent()) {
            BookingHistory history = existing.get();
            if (!ownedBy(history, user, event)) {
                return;
            }
            switch (history.getStatus()) {
                case BOOKED -> {
                    history.changeStatus(BookingStatus.COMPLETED);
                    creditEvaluationService.applyAttendanceChange(user, event, REASON_CHECK_IN);
                }
                case NO_SHOW -> {
                    history.changeStatus(BookingStatus.COMPLETED);
                    creditEvaluationService.applyAttendanceChange(user, event, REASON_LATE_CHECK_IN);
                }
                case COMPLETED -> { } // 같은 티켓의 다른 CHECK_IN 이벤트: 이미 반영됨
                default -> log.warn("CHECK_IN event {} ignored: ticket history {} is {}",
                        event.getEventId(), history.getBookingId(), history.getStatus());
            }
            return;
        }

        Schedule schedule = schedule(event);
        LocalDateTime checkedInAt = seconds(event.getOccurredAt());
        // 예매 정보는 뒤늦은 BOOKING이 채운다. 그때까지 예매 번호·예매 시각·공연 시각은 CHECK_IN에서 아는 값으로 둔다.
        bookingHistoryRepository.save(new BookingHistory(user, event.getServiceId(), ticketId,
                event.getBookingId() != null ? event.getBookingId() : ticketId, schedule.concertId(),
                BookingStatus.COMPLETED, checkedInAt,
                schedule.startsAt() != null ? schedule.startsAt() : checkedInAt, schedule.endsAt()));
        creditEvaluationService.applyAttendanceChange(user, event, REASON_CHECK_IN);
    }

    // 같은 사용자의 이력 변경과 Credit 갱신이 겹치지 않도록 user_credit 행을 잠근다.
    private void lockUser(UserAccount user) {
        userCreditRepository.findByIdForUpdate(user.getUserId());
    }

    private boolean ownedBy(BookingHistory history, UserAccount user, BehaviorEvent event) {
        if (history.getUser().getUserId().equals(user.getUserId())) {
            return true;
        }
        log.warn("Event {} ignored for booking history {}: ticket belongs to another user",
                event.getEventId(), history.getBookingId());
        return false;
    }

    private static List<String> ticketIds(BehaviorEvent event) {
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null || !(metadata.get("ticketIds") instanceof List<?> values)) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String id && !id.isBlank() && id.length() <= MAX_TICKET_ID_LENGTH) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    // 공연 정보는 이벤트 metadata(예매 당시 값)를 우선하고, 없으면 등록된 공연 정보를 쓴다.
    private Schedule schedule(BehaviorEvent event) {
        Map<String, Object> metadata = event.getMetadata();
        String concertId = null;
        LocalDateTime startsAt = null;
        LocalDateTime endsAt = null;
        if (metadata != null) {
            if (metadata.get("concertId") instanceof String id && !id.isBlank()
                    && id.length() <= MAX_CONCERT_ID_LENGTH) {
                concertId = id;
            }
            startsAt = utc(metadata.get("performanceStartsAt"));
            endsAt = utc(metadata.get("performanceEndsAt"));
        }
        if (concertId != null && (startsAt == null || endsAt == null)) {
            Optional<ConcertResponse> concert = concertRepository.find(event.getServiceId(), concertId);
            if (concert.isPresent()) {
                if (startsAt == null) {
                    startsAt = utc(concert.get().startsAt());
                }
                if (endsAt == null) {
                    endsAt = utc(concert.get().endsAt());
                }
            }
        }
        return new Schedule(concertId, startsAt, endsAt);
    }

    private static LocalDateTime utc(Object value) {
        if (value instanceof OffsetDateTime time) {
            return seconds(time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return utc(OffsetDateTime.parse(text));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    // booking_history의 시각 컬럼은 초 단위 DATETIME이다. DB 반올림에 맡기지 않고 버린다.
    private static LocalDateTime seconds(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.SECONDS);
    }

    private record Schedule(String concertId, LocalDateTime startsAt, LocalDateTime endsAt) { }
}
