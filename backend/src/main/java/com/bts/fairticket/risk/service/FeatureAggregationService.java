package com.bts.fairticket.risk.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.behavior.entity.BehaviorEvent;
import com.bts.fairticket.behavior.repository.BehaviorEventRepository;
import com.bts.fairticket.risk.dto.UserCalculatedFeatures;

import lombok.RequiredArgsConstructor;

/**
 * ticket-fds-ai POST /predict 요청용 Feature 6개를 behavior_event에서 집계한다.
 * 계산 규칙은 ticket-fds-ai preprocessing.py FEATURE_SPECS와 docs/predict_feature_spec.md를 따른다.
 */
@Service
@RequiredArgsConstructor
public class FeatureAggregationService {

    public static final int WINDOW_DAYS = 30;
    /** ticket-fds-ai 최종 모델(#20): 간격 분산은 간격 5개(시도 6건) 이상일 때만 계산하고, 그 미만은 null로 보낸다. */
    static final int MIN_INTERVALS_FOR_VARIANCE = 5;
    /** Credit 룰 감점(IP 집중도)의 집계 구간: 최근 10분. */
    static final int RECENT_IP_WINDOW_MINUTES = 10;
    static final String EVENT_BOOKING_ATTEMPT = "BOOKING_ATTEMPT";
    static final String META_ATTEMPT_ID = "attemptId";
    static final String META_PAYMENT_IDENTIFIER = "paymentIdentifier";
    static final String META_IP_ADDRESS = "ipAddress";

    private final BehaviorEventRepository behaviorEventRepository;
    private final Clock clock; // 테스트 시 시간 고정을 위해 Clock 주입 (기본 ZoneOffset.UTC)

    @Transactional(readOnly = true)
    public UserCalculatedFeatures aggregateFeatures(String userId, LocalDateTime calculatedAtUtc) {
        LocalDateTime windowStartUtc = calculatedAtUtc.minusDays(WINDOW_DAYS);

        // calculatedAt보다 미래인 이벤트는 제외하고 최근 30일(start <= occurredAt <= end) 조회
        List<BehaviorEvent> events = behaviorEventRepository
                .findByUser_UserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanEqualOrderByOccurredAtAscEventIdAsc(
                        userId, windowStartUtc, calculatedAtUtc);

        return calculate(events, windowStartUtc, calculatedAtUtc);
    }

    /**
     * Credit 룰 감점 입력값 recentIpAttemptCount: 분석 시각 직전 10분 동안 같은 IP에서 들어온 고유 예매 시도 수 (#63).
     * AI 모델에 보내는 Feature가 아니다. ip_booking_density(30일, 예매 완료 기준 고유 계정 수)와 정의가 다르므로
     * 서로 대신 쓰지 않는다. 예매 시도 이벤트에 IP가 없으면 null을 돌려주고, 그 경우 감점하지 않는다.
     */
    @Transactional(readOnly = true)
    public Integer recentIpAttemptCount(String userId, LocalDateTime calculatedAtUtc) {
        return behaviorEventRepository.maxRecentAttemptsFromUserIps(
                userId, calculatedAtUtc.minusMinutes(RECENT_IP_WINDOW_MINUTES), calculatedAtUtc);
    }

    /**
     * @param events 한 사용자의 분석 기간 내 이벤트 (occurredAt, eventId 오름차순)
     */
    public UserCalculatedFeatures calculate(List<BehaviorEvent> events, LocalDateTime windowStart, LocalDateTime windowEnd) {
        // serviceId + bookingId 기준 최초 BOOKING 이벤트 (시간순 보존): 취소율, 결제수단·IP 집계용
        Map<String, BehaviorEvent> distinctBookings = new LinkedHashMap<>();
        // serviceId + bookingId 기준 취소 키
        Set<String> distinctCancels = new HashSet<>();
        // serviceId + attemptId 기준 시도 시작 시각 (가장 이른 단계 이벤트, 시간순 보존): 시도 수, 간격·분산용
        Map<String, LocalDateTime> attemptStarts = new LinkedHashMap<>();

        for (BehaviorEvent event : events) {
            if (event.getServiceId() == null) {
                continue;
            }

            if (EVENT_BOOKING_ATTEMPT.equalsIgnoreCase(event.getEventType())) {
                // 한 번의 시도는 단계(STARTED, QUEUE, SEAT_HOLD 등)마다 이벤트가 생기므로 attemptId로 묶어 1건으로 센다
                String attemptId = metadataString(event, META_ATTEMPT_ID);
                if (attemptId != null) {
                    attemptStarts.putIfAbsent(event.getServiceId() + ":" + attemptId, event.getOccurredAt());
                }
                continue;
            }

            if (event.getBookingId() == null) {
                continue;
            }

            String bookingKey = event.getServiceId() + ":" + event.getBookingId();

            if ("BOOKING".equalsIgnoreCase(event.getEventType())) {
                // 동일한 serviceId와 bookingId 조합은 최초 1회만 집계
                distinctBookings.putIfAbsent(bookingKey, event);
            } else if ("CANCEL".equalsIgnoreCase(event.getEventType())) {
                distinctCancels.add(bookingKey);
            }
        }

        List<BehaviorEvent> bookings = new ArrayList<>(distinctBookings.values());

        // cancel_rate: 최근 30일 예매(완료) 중 분석 시각까지 취소된 건수 / 최근 30일 전체 예매(완료) 건수
        Double cancelRate = null;
        if (!bookings.isEmpty()) {
            long canceledBookingCount = distinctBookings.keySet().stream()
                    .filter(distinctCancels::contains)
                    .count();
            cancelRate = (double) canceledBookingCount / bookings.size();
        }

        // booking_count: 최근 30일 고유 예매 시도(attemptId) 수 (#40 확정: BOOKING_ATTEMPT 기준)
        List<LocalDateTime> attemptTimes = attemptStarts.values().stream().sorted().toList();
        int bookingCount = attemptTimes.size();

        // avg_booking_interval: 연속 시도 시작 시각 간격의 평균, 시도 2건 미만이면 null
        // booking_interval_variance: 같은 간격의 모분산, 간격 5개 미만(시도 6건 미만)이면 null (ticket-fds-ai #20)
        Double avgBookingInterval = null;
        Double bookingIntervalVariance = null;
        if (bookingCount >= 2) {
            double[] intervals = new double[bookingCount - 1];
            for (int i = 1; i < bookingCount; i++) {
                Duration diff = Duration.between(attemptTimes.get(i - 1), attemptTimes.get(i));
                intervals[i - 1] = Math.max(0L, diff.toMillis()) / 1000.0;
            }
            avgBookingInterval = mean(intervals);
            if (intervals.length >= MIN_INTERVALS_FOR_VARIANCE) {
                bookingIntervalVariance = populationVariance(intervals, avgBookingInterval);
            }
        }

        return UserCalculatedFeatures.builder()
                .bookingCount(bookingCount)
                .avgBookingInterval(avgBookingInterval)
                .bookingIntervalVariance(bookingIntervalVariance)
                .cancelRate(cancelRate)
                .cardReuseCount(maxSharedAccounts(bookings, META_PAYMENT_IDENTIFIER, windowStart, windowEnd))
                .ipBookingDensity(maxSharedAccounts(bookings, META_IP_ADDRESS, windowStart, windowEnd))
                .build();
    }

    /**
     * 사용자가 예매에 쓴 metadata 값(결제수단 또는 IP)마다, 같은 서비스에서 기간 내 같은 값으로 예매한
     * 고유 계정 수를 세어 최댓값을 돌려준다. 본인을 포함하므로 최소 1이다 (AI 스펙상 null 불가, 기본 1).
     */
    private int maxSharedAccounts(List<BehaviorEvent> bookings, String metadataKey,
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        Set<List<String>> serviceAndValues = new LinkedHashSet<>();
        for (BehaviorEvent booking : bookings) {
            String value = metadataString(booking, metadataKey);
            if (value != null) {
                serviceAndValues.add(List.of(booking.getServiceId(), value));
            }
        }

        long max = 1;
        for (List<String> serviceAndValue : serviceAndValues) {
            String serviceId = serviceAndValue.get(0);
            String value = serviceAndValue.get(1);
            long count = META_PAYMENT_IDENTIFIER.equals(metadataKey)
                    ? behaviorEventRepository.countDistinctUsersByPaymentIdentifier(serviceId, value, windowStart, windowEnd)
                    : behaviorEventRepository.countDistinctUsersByIpAddress(serviceId, value, windowStart, windowEnd);
            max = Math.max(max, count);
        }
        return Math.toIntExact(max);
    }

    private static String metadataString(BehaviorEvent event, String key) {
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double populationVariance(double[] values, double mean) {
        double sumOfSquares = 0;
        for (double value : values) {
            double diff = value - mean;
            sumOfSquares += diff * diff;
        }
        return sumOfSquares / values.length;
    }
}
