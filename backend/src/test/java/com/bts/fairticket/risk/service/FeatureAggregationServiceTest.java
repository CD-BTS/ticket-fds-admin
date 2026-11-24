package com.bts.fairticket.risk.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bts.fairticket.behavior.entity.BehaviorEvent;
import com.bts.fairticket.behavior.repository.BehaviorEventRepository;
import com.bts.fairticket.risk.dto.UserCalculatedFeatures;
import com.bts.fairticket.user.entity.UserAccount;

@ExtendWith(MockitoExtension.class)
class FeatureAggregationServiceTest {

    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 10, 31, 0, 0, 0);
    private static final LocalDateTime WINDOW_START = WINDOW_END.minusDays(30);
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 15, 10, 0, 0);

    @Mock
    private BehaviorEventRepository behaviorEventRepository;

    private FeatureAggregationService service;
    private UserAccount mockUser;
    private int eventSeq;

    @BeforeEach
    void setUp() {
        service = new FeatureAggregationService(behaviorEventRepository, Clock.systemUTC());
        mockUser = new UserAccount("user_001", "service_A", "ext_001");
        eventSeq = 0;
    }

    private UserCalculatedFeatures calculate(BehaviorEvent... events) {
        return service.calculate(List.of(events), WINDOW_START, WINDOW_END);
    }

    private BehaviorEvent booking(String bookingId, LocalDateTime at) {
        return booking(bookingId, at, null);
    }

    private BehaviorEvent booking(String bookingId, LocalDateTime at, Map<String, Object> metadata) {
        return new BehaviorEvent(mockUser, "service_A", "evt_" + (++eventSeq), "BOOKING", bookingId, null, at, metadata);
    }

    private BehaviorEvent cancel(String bookingId, LocalDateTime at) {
        return new BehaviorEvent(mockUser, "service_A", "evt_" + (++eventSeq), "CANCEL", bookingId, null, at, null);
    }

    private BehaviorEvent attempt(String attemptId, String stage, LocalDateTime at) {
        return attempt("service_A", attemptId, stage, at);
    }

    private BehaviorEvent attempt(String serviceId, String attemptId, String stage, LocalDateTime at) {
        Map<String, Object> metadata = Map.of("attemptId", attemptId, "stage", stage);
        return new BehaviorEvent(mockUser, serviceId, "evt_" + (++eventSeq), "BOOKING_ATTEMPT", null, null, at, metadata);
    }

    @Test
    @DisplayName("이벤트가 없으면 bookingCount=0, 간격·분산·취소율은 null, 카드·IP는 기본값 1")
    void calculate_emptyEvents() {
        UserCalculatedFeatures result = service.calculate(Collections.emptyList(), WINDOW_START, WINDOW_END);

        assertEquals(0, result.getBookingCount());
        assertNull(result.getAvgBookingInterval());
        assertNull(result.getBookingIntervalVariance());
        assertNull(result.getCancelRate());
        assertEquals(1, result.getCardReuseCount());
        assertEquals(1, result.getIpBookingDensity());
    }

    @Test
    @DisplayName("bookingCount는 BOOKING이 아니라 고유 attemptId 수로 센다")
    void calculate_bookingCountUsesAttempts() {
        UserCalculatedFeatures result = calculate(
                booking("b1", T0),
                booking("b2", T0.plusSeconds(60)),
                attempt("a1", "STARTED", T0.minusSeconds(30)));

        assertEquals(1, result.getBookingCount());
        assertNull(result.getAvgBookingInterval());
        assertNull(result.getBookingIntervalVariance());
    }

    @Test
    @DisplayName("한 시도의 여러 단계 이벤트는 1건이고, 시작 시각은 가장 이른 단계 이벤트")
    void calculate_attemptStagesCollapseToOneAttempt() {
        UserCalculatedFeatures result = calculate(
                attempt("a1", "STARTED", T0),
                attempt("a1", "QUEUE", T0.plusSeconds(5)),
                attempt("a1", "SEAT_HOLD", T0.plusSeconds(20)),
                attempt("a2", "STARTED", T0.plusSeconds(30)),
                attempt("a2", "SEAT_HOLD", T0.plusSeconds(31)));

        assertEquals(2, result.getBookingCount());
        assertEquals(30.0, result.getAvgBookingInterval(), 0.0001); // a1 시작(0초) → a2 시작(30초)
        assertNull(result.getBookingIntervalVariance());           // 간격 1개 < 5
    }

    @Test
    @DisplayName("시도 간격 평균·모분산을 밀리초 정밀도로 계산 (간격 5개 이상)")
    void calculate_attemptIntervalsWithMillis() {
        // 간격: 0.2, 0.4, 0.2, 0.4, 0.2, 0.4초 (시도 7건, 간격 6개)
        long[] offsetsMillis = {0, 200, 600, 800, 1200, 1400, 1800};
        BehaviorEvent[] events = new BehaviorEvent[offsetsMillis.length];
        for (int i = 0; i < offsetsMillis.length; i++) {
            events[i] = attempt("a" + i, "STARTED", T0.plusNanos(offsetsMillis[i] * 1_000_000));
        }
        UserCalculatedFeatures result = calculate(events);

        assertEquals(7, result.getBookingCount());
        assertEquals(0.3, result.getAvgBookingInterval(), 0.0001);        // 평균 0.3초
        assertEquals(0.01, result.getBookingIntervalVariance(), 0.0001);  // 각 편차 ±0.1 → 모분산 0.01
    }

    @Test
    @DisplayName("간격이 5개 미만이면 평균만 계산하고 분산은 null (시도 5건 → 간격 4개)")
    void calculate_varianceRequiresFiveIntervals() {
        BehaviorEvent[] five = new BehaviorEvent[5];
        for (int i = 0; i < 5; i++) five[i] = attempt("b" + i, "STARTED", T0.plusSeconds(10L * i));
        UserCalculatedFeatures result = calculate(five);
        assertEquals(10.0, result.getAvgBookingInterval(), 0.0001);
        assertNull(result.getBookingIntervalVariance());

        BehaviorEvent[] six = new BehaviorEvent[6];
        for (int i = 0; i < 6; i++) six[i] = attempt("c" + i, "STARTED", T0.plusSeconds(10L * i));
        assertEquals(0.0, calculate(six).getBookingIntervalVariance(), 0.0001);
    }

    @Test
    @DisplayName("attemptId가 같아도 서비스가 다르면 다른 시도, attemptId가 없으면 집계 제외")
    void calculate_attemptKeyIncludesServiceAndRequiresAttemptId() {
        BehaviorEvent noAttemptId = new BehaviorEvent(mockUser, "service_A", "evt_x", "BOOKING_ATTEMPT", null, null,
                T0.plusSeconds(5), Map.of("stage", "STARTED"));

        UserCalculatedFeatures result = calculate(
                attempt("service_A", "a1", "STARTED", T0),
                noAttemptId,
                attempt("service_B", "a1", "STARTED", T0.plusSeconds(10)));

        assertEquals(2, result.getBookingCount());
        assertEquals(10.0, result.getAvgBookingInterval(), 0.0001);
    }

    @Test
    @DisplayName("취소율은 BOOKING 완료 기준이고 중복 BOOKING은 1건으로 집계")
    void calculate_cancelRateUsesDistinctBookings() {
        UserCalculatedFeatures result = calculate(
                booking("b1", T0),
                booking("b1", T0.plusSeconds(2)),
                booking("b2", T0.plusSeconds(10)),
                booking("b3", T0.plusSeconds(20)),
                cancel("b1", T0.plusSeconds(30)));

        assertEquals(1.0 / 3.0, result.getCancelRate(), 0.0001);
        assertEquals(0, result.getBookingCount());
    }

    @Test
    @DisplayName("결제수단·IP별 공유 계정 수 중 최댓값을 사용")
    void calculate_cardReuseAndIpDensityUseMaxSharedAccounts() {
        BehaviorEvent b1 = booking("b1", T0, Map.of("paymentIdentifier", "card-A", "ipAddress", "10.0.0.1"));
        BehaviorEvent b2 = booking("b2", T0.plusSeconds(60), Map.of("paymentIdentifier", "card-B", "ipAddress", "10.0.0.2"));

        when(behaviorEventRepository.countDistinctUsersByPaymentIdentifier("service_A", "card-A", WINDOW_START, WINDOW_END)).thenReturn(1L);
        when(behaviorEventRepository.countDistinctUsersByPaymentIdentifier("service_A", "card-B", WINDOW_START, WINDOW_END)).thenReturn(4L);
        when(behaviorEventRepository.countDistinctUsersByIpAddress("service_A", "10.0.0.1", WINDOW_START, WINDOW_END)).thenReturn(6L);
        when(behaviorEventRepository.countDistinctUsersByIpAddress("service_A", "10.0.0.2", WINDOW_START, WINDOW_END)).thenReturn(2L);

        UserCalculatedFeatures result = calculate(b1, b2);

        assertEquals(4, result.getCardReuseCount());
        assertEquals(6, result.getIpBookingDensity());
    }

    @Test
    @DisplayName("같은 결제수단을 여러 번 써도 조회는 한 번만 하고, 결과가 0이어도 최소 1")
    void calculate_sameIdentifierQueriedOnceAndAtLeastOne() {
        Map<String, Object> metadata = Map.of("paymentIdentifier", "card-A");

        when(behaviorEventRepository.countDistinctUsersByPaymentIdentifier(eq("service_A"), eq("card-A"), any(), any())).thenReturn(0L);

        UserCalculatedFeatures result = calculate(booking("b1", T0, metadata), booking("b2", T0.plusSeconds(60), metadata));

        assertEquals(1, result.getCardReuseCount());
        assertEquals(1, result.getIpBookingDensity());
        verify(behaviorEventRepository).countDistinctUsersByPaymentIdentifier("service_A", "card-A", WINDOW_START, WINDOW_END);
        verify(behaviorEventRepository, never()).countDistinctUsersByIpAddress(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("metadata가 없거나 값이 비어 있으면 조회하지 않고 기본값 1")
    void calculate_missingMetadataDefaultsToOne() {
        UserCalculatedFeatures result = calculate(
                booking("b1", T0),
                booking("b2", T0.plusSeconds(60), Map.of("paymentIdentifier", " ", "ipAddress", "")));

        assertEquals(1, result.getCardReuseCount());
        assertEquals(1, result.getIpBookingDensity());
        verify(behaviorEventRepository, never()).countDistinctUsersByPaymentIdentifier(anyString(), anyString(), any(), any());
        verify(behaviorEventRepository, never()).countDistinctUsersByIpAddress(anyString(), anyString(), any(), any());
    }
}
