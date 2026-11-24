package com.bts.fairticket.concert.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.admin.dto.ConcertTrendResponse;
import com.bts.fairticket.admin.repository.AdminStatisticsRepository;
import com.bts.fairticket.admin.service.AdminUserQueryService;
import com.bts.fairticket.common.auth.AdminScope;
import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.PageResponse;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.concert.dto.ConcertDetailResponse;
import com.bts.fairticket.concert.dto.ConcertListItemResponse;
import com.bts.fairticket.concert.dto.ConcertQuery;
import com.bts.fairticket.concert.dto.ConcertRequest;
import com.bts.fairticket.concert.dto.ConcertResponse;
import com.bts.fairticket.concert.dto.ConcertUserResponse;
import com.bts.fairticket.concert.repository.ConcertRepository;
import com.bts.fairticket.user.dto.UserQuery;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ConcertService {
    /** 추이 집계 간격(초). AI 평가가 기본 60초 주기라 60초보다 잘게 나누지 않는다 (#67). */
    static final Set<Integer> TREND_INTERVALS = Set.of(60, 300, 600, 3600);
    static final int DEFAULT_TREND_INTERVAL = 60;
    static final int MAX_TREND_BUCKETS = 500;
    private static final int DEFAULT_TREND_BUCKETS = 60;
    /** 시도의 마지막 상태가 이 값이면 실패로 센다. 그 밖(ENTERED, COMPLETED가 아닌 단계의 SUCCEEDED)은 진행 중이다. */
    private static final Set<String> FAILURE_STATUSES = Set.of("REJECTED", "FAILED", "ABANDONED", "EXPIRED");

    private final ConcertRepository repository;
    private final AdminUserQueryService users;
    private final AdminStatisticsRepository statistics;
    private final Clock clock;

    public ConcertService(ConcertRepository repository, AdminUserQueryService users,
            AdminStatisticsRepository statistics, Clock clock) {
        this.repository = repository; this.users = users; this.statistics = statistics; this.clock = clock;
    }

    @Transactional
    public ConcertResponse upsert(String serviceId, String concertId, ConcertRequest request) {
        repository.upsert(serviceId, concertId, request);
        return require(serviceId, concertId);
    }

    public PageResponse<ConcertListItemResponse> list(String serviceId, ConcertQuery query, AdminScope scope) {
        scope.restrict(serviceId);
        var page = repository.list(serviceId, query);
        // 페이지의 공연들을 한 번의 쿼리로 집계한다.
        Map<String, Long> anomalies = statistics.anomalyUsersByConcert(serviceId,
                page.content().stream().map(ConcertResponse::concertId).toList());
        return PageResponse.of(page.content().stream()
                .map(concert -> ConcertListItemResponse.from(concert, anomalies.getOrDefault(concert.concertId(), 0L)))
                .toList(), page.page(), page.size(), page.totalElements());
    }

    public ConcertDetailResponse detail(String serviceId, String concertId, AdminScope scope) {
        scope.restrict(serviceId);
        var concert = require(serviceId, concertId);
        var counts = repository.statistics(serviceId, concertId);

        // 성공 · 실패 · 진행 중은 같은 모집단(시작이 확인된 고유 시도)을 마지막 상태로 나눈 것이다.
        long success = 0, failure = 0, inProgress = 0;
        Map<String, Long> failures = new LinkedHashMap<>();
        for (var outcome : statistics.attemptOutcomes(serviceId, concertId).entrySet()) {
            String status = outcome.getKey().substring(outcome.getKey().indexOf('/') + 1);
            if (outcome.getKey().equals("COMPLETED/SUCCEEDED")) {
                success += outcome.getValue();
            } else if (FAILURE_STATUSES.contains(status)) {
                failure += outcome.getValue();
                failures.put(outcome.getKey(), outcome.getValue());
            } else {
                inProgress += outcome.getValue();
            }
        }
        var risk = statistics.concertRisk(serviceId, concertId);
        return new ConcertDetailResponse(concert, counts[0], counts[1], success, failure, inProgress, failures,
                statistics.distinctBookings(serviceId, concertId, "BOOKING"),
                statistics.distinctBookings(serviceId, concertId, "CANCEL"),
                statistics.checkedInTickets(serviceId, concertId),
                risk.assessedUsers(), risk.unassessedUsers(), risk.anomalyUsers(), risk.averageCreditScore(),
                risk.usersByExpectedPolicy(), risk.expectedSanctionUsers(),
                statistics.concertSanctions(serviceId, concertId));
    }

    public PageResponse<ConcertUserResponse> users(String serviceId, String concertId, UserQuery query, AdminScope scope) {
        scope.restrict(serviceId);
        require(serviceId, concertId);
        var page = users.findForConcert(query, scope, concertId);
        var activity = repository.lastActivities(serviceId, concertId,
                page.content().stream().map(user -> user.userId()).toList());
        return PageResponse.of(page.content().stream()
                .map(user -> new ConcertUserResponse(user, activity.get(user.userId()))).toList(),
                page.page(), page.size(), page.totalElements());
    }

    /**
     * 시간대별 추이(#67). from(포함) ~ to(제외)를 intervalSeconds로 나눈다. from은 간격의 배수로 내림하고,
     * to가 없으면 지금, from이 없으면 to에서 60개 버킷 앞이다. 버킷은 최대 500개다.
     */
    public ConcertTrendResponse trends(String serviceId, String concertId, String from, String to,
            Integer intervalSeconds, AdminScope scope) {
        scope.restrict(serviceId);
        require(serviceId, concertId);
        int interval = intervalSeconds == null ? DEFAULT_TREND_INTERVAL : intervalSeconds;
        if (!TREND_INTERVALS.contains(interval)) {
            throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
        }
        long toEpoch = to == null ? clock.instant().getEpochSecond() : epochSecond(to);
        long fromEpoch = from == null ? toEpoch - (long) interval * DEFAULT_TREND_BUCKETS : epochSecond(from);
        if (toEpoch <= fromEpoch) {
            throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
        }
        fromEpoch -= Math.floorMod(fromEpoch, interval);
        long bucketCount = (toEpoch - fromEpoch + interval - 1) / interval;
        if (bucketCount > MAX_TREND_BUCKETS) {
            throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
        }
        LocalDateTime fromUtc = LocalDateTime.ofEpochSecond(fromEpoch, 0, ZoneOffset.UTC);
        LocalDateTime toUtc = LocalDateTime.ofEpochSecond(toEpoch, 0, ZoneOffset.UTC);
        var counts = statistics.trends(serviceId, concertId, fromUtc, toUtc, interval);
        List<ConcertTrendResponse.Bucket> buckets = new ArrayList<>();
        for (long index = 0; index < bucketCount; index++) {
            buckets.add(new ConcertTrendResponse.Bucket(Instant.ofEpochSecond(fromEpoch + index * interval).toString(),
                    counts.attempts().getOrDefault(index, 0L), counts.anomalyAssessments().getOrDefault(index, 0L),
                    counts.sanctions().getOrDefault(index, 0L)));
        }
        return new ConcertTrendResponse(serviceId, concertId, Instant.ofEpochSecond(fromEpoch).toString(),
                Instant.ofEpochSecond(toEpoch).toString(), interval,
                statistics.concertSanctions(serviceId, concertId).recorded(), buckets);
    }

    // UTC 오프셋이 붙은 ISO-8601만 받는다. 연도 범위를 벗어나면 조회 조건 오류다.
    private static long epochSecond(String value) {
        try {
            OffsetDateTime time = OffsetDateTime.parse(value);
            int year = time.withOffsetSameInstant(ZoneOffset.UTC).getYear();
            if (year < 1000 || year > 9999) {
                throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
            }
            return time.toEpochSecond();
        } catch (DateTimeParseException exception) {
            throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
        }
    }

    private ConcertResponse require(String serviceId, String concertId) {
        return repository.find(serviceId, concertId)
                .orElseThrow(() -> new ApiException(ResponseCode.CONCERT_NOT_FOUND));
    }
}
