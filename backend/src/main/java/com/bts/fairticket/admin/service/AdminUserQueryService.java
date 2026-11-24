package com.bts.fairticket.admin.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.admin.dto.AdminCreditHistoryResponse;
import com.bts.fairticket.admin.dto.AdminUserDetailResponse;
import com.bts.fairticket.admin.dto.AdminUserResponse;
import com.bts.fairticket.admin.dto.AssessmentAttemptResponse;
import com.bts.fairticket.admin.dto.AssessmentQuery;
import com.bts.fairticket.admin.dto.AssessmentResponse;
import com.bts.fairticket.admin.dto.CreditCalculationResponse;
import com.bts.fairticket.admin.dto.UserFeaturesResponse;
import com.bts.fairticket.admin.repository.AdminAssessmentQueryRepository;
import com.bts.fairticket.behavior.dto.EventQuery;
import com.bts.fairticket.behavior.dto.EventResponse;
import com.bts.fairticket.behavior.service.UserEventQueryService;
import com.bts.fairticket.common.auth.AdminScope;
import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.PageResponse;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.dto.BookingHistoryQuery;
import com.bts.fairticket.credit.dto.BookingHistoryResponse;
import com.bts.fairticket.credit.entity.CreditCalculation;
import com.bts.fairticket.credit.repository.CreditCalculationRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.credit.service.BookingHistoryQueryService;
import com.bts.fairticket.credit.service.CreditQueryService;
import com.bts.fairticket.policy.dto.PolicyDecisionResponse;
import com.bts.fairticket.policy.repository.PolicyDecisionRepository;
import com.bts.fairticket.policy.service.PolicyDecisionService;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.entity.RiskAssessmentAttempt;
import com.bts.fairticket.risk.repository.RiskAssessmentAttemptRepository;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.risk.service.FeatureAggregationService;
import com.bts.fairticket.user.dto.UserQuery;
import com.bts.fairticket.user.entity.UserAccount;
import com.bts.fairticket.user.repository.UserAccountRepository;
import com.bts.fairticket.user.service.UserQueryService;
import com.bts.fairticket.user.service.UserService;

/** 관리자 조회. 모든 메서드는 읽기 전용이며 Credit, 평가, 정책 결정을 만들거나 바꾸지 않는다. */
@Service
@Transactional(readOnly = true)
public class AdminUserQueryService {
    private final UserQueryService users;
    private final UserService details;
    private final UserEventQueryService events;
    private final UserAccountRepository accounts;
    private final RiskAssessmentRepository assessments;
    private final RiskAssessmentAttemptRepository attempts;
    private final PolicyDecisionRepository policies;
    private final AdminAssessmentQueryRepository history;
    private final FeatureAggregationService features;
    private final BookingHistoryQueryService bookings;
    private final CreditQueryService credits;
    private final UserCreditRepository userCredits;
    private final CreditCalculationRepository calculations;
    private final Clock clock;

    public AdminUserQueryService(UserQueryService users, UserService details, UserEventQueryService events,
            UserAccountRepository accounts, RiskAssessmentRepository assessments,
            RiskAssessmentAttemptRepository attempts,
            PolicyDecisionRepository policies, AdminAssessmentQueryRepository history,
            FeatureAggregationService features, BookingHistoryQueryService bookings,
            CreditQueryService credits, UserCreditRepository userCredits, CreditCalculationRepository calculations,
            Clock clock) {
        this.users = users; this.details = details; this.events = events; this.accounts = accounts;
        this.assessments = assessments; this.attempts = attempts; this.policies = policies; this.history = history;
        this.features = features; this.bookings = bookings; this.credits = credits;
        this.userCredits = userCredits; this.calculations = calculations; this.clock = clock;
    }

    public PageResponse<AdminUserResponse> find(UserQuery query, AdminScope scope) {
        return enrich(users.find(query, scope.restrict(query.serviceId())));
    }

    public PageResponse<AdminUserResponse> findForConcert(UserQuery query, AdminScope scope, String concertId) {
        return enrich(users.findForConcert(query, scope.restrict(query.serviceId()), concertId));
    }

    private PageResponse<AdminUserResponse> enrich(PageResponse<com.bts.fairticket.user.dto.UserResponse> page) {
        List<String> ids = page.content().stream().map(user -> user.userId()).toList();
        // 페이지 단위로 한 번씩만 조회한다(N+1 없음).
        Map<String, RiskAssessment> latest = ids.isEmpty() ? Map.of() : assessments.findLatestForUsers(ids).stream()
                .collect(Collectors.toMap(a -> a.getUser().getUserId(), Function.identity()));
        Map<String, RiskAssessmentAttempt> latestAttempts = ids.isEmpty() ? Map.of()
                : attempts.findLatestForUsers(ids).stream()
                        .collect(Collectors.toMap(RiskAssessmentAttempt::getUserId, Function.identity()));
        return PageResponse.of(page.content().stream()
                .map(user -> AdminUserResponse.from(user, latest.get(user.userId()), latestAttempts.get(user.userId())))
                .toList(), page.page(), page.size(), page.totalElements());
    }

    public AdminUserDetailResponse detail(String userId, AdminScope scope) {
        var user = authorizedUser(userId, scope);
        var detail = details.getUser(user.getUserId(), user.getServiceId());
        var policy = policies.findFirstByUser_UserIdOrderByDecidedAtDescDecisionIdDesc(user.getUserId())
                .map(PolicyDecisionResponse::from).orElse(null);

        RiskAssessmentAttempt latestAttempt = attempts.findFirstByUserIdOrderByAttemptIdDesc(user.getUserId()).orElse(null);
        RiskAssessment linked = latestAttempt == null || latestAttempt.getAssessmentId() == null ? null
                : assessments.findById(latestAttempt.getAssessmentId()).orElse(null);
        var credit = userCredits.findById(user.getUserId()).orElse(null);
        CreditCalculation calculation = credit == null || credit.getLatestCalculationId() == null ? null
                : calculations.findById(credit.getLatestCalculationId()).orElse(null);

        return new AdminUserDetailResponse(detail, policy,
                AdminUserResponse.assessmentStatus(latestAttempt, detail.lastAssessedAt() != null),
                latestAttempt == null ? null : AssessmentAttemptResponse.from(latestAttempt, linked),
                // 조회용 계산이다. policy_decision 행을 만들지 않는다.
                PolicyDecisionService.expectedAction(detail.creditScore(), detail.anomalyScore()),
                credit == null ? null : credit.getDynamicPenalty(),
                CreditCalculationResponse.from(calculation));
    }

    public PageResponse<EventResponse> events(String userId, EventQuery query, AdminScope scope) {
        var user = authorizedUser(userId, scope);
        return events.find(user.getUserId(), user.getServiceId(), query);
    }

    public PageResponse<BookingHistoryResponse> bookings(String userId, BookingHistoryQuery query, AdminScope scope) {
        var user = authorizedUser(userId, scope);
        return bookings.find(user.getUserId(), user.getServiceId(), query);
    }

    public PageResponse<AssessmentResponse> assessments(String userId, AssessmentQuery query, AdminScope scope) {
        return history.find(authorizedUser(userId, scope).getUserId(), query);
    }

    /** 평가 시도 이력(#67): 성공한 평가뿐 아니라 데이터 부족 · INVALID로 끝난 시도도 최신순으로 돌려준다. */
    public PageResponse<AssessmentAttemptResponse> assessmentAttempts(String userId, AssessmentQuery query,
            AdminScope scope) {
        return history.findAttempts(authorizedUser(userId, scope).getUserId(), query);
    }

    /**
     * Credit 변경 이력과 산출 근거(#67). 이력 조회는 서비스용 Credit 이력 API와 같은 로직을 쓴다.
     * 산출 근거는 저장된 값만 붙인다. #67 이전 이력은 근거가 없으며 다시 계산하지 않는다.
     */
    public PageResponse<AdminCreditHistoryResponse> creditHistory(String userId, int page, int size,
            String startDate, String endDate, AdminScope scope) {
        var user = authorizedUser(userId, scope);
        var histories = credits.findHistory(user.getUserId(), page, size, startDate, endDate);
        List<Long> ids = histories.getContent().stream().map(h -> h.getHistoryId()).toList();
        Map<Long, CreditCalculation> byHistory = ids.isEmpty() ? Map.of() : calculations.findByHistoryIdIn(ids).stream()
                .collect(Collectors.toMap(CreditCalculation::getHistoryId, Function.identity(), (a, b) -> a));
        return PageResponse.of(histories.getContent().stream()
                .map(h -> AdminCreditHistoryResponse.from(h, byHistory.get(h.getHistoryId()))).toList(),
                histories.getNumber(), histories.getSize(), histories.getTotalElements());
    }

    /** 조회 전용: 평가·정책 생성이나 Credit 갱신 없이 최근 30일 이벤트로 Feature만 계산한다. */
    public UserFeaturesResponse features(String userId, AdminScope scope) {
        var user = authorizedUser(userId, scope);
        LocalDateTime calculatedAt = LocalDateTime.now(clock);
        return UserFeaturesResponse.of(user.getUserId(), calculatedAt,
                features.aggregateFeatures(user.getUserId(), calculatedAt));
    }

    private UserAccount authorizedUser(String userId, AdminScope scope) {
        var user = accounts.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.USER_NOT_FOUND));
        // Hide out-of-scope users with the same response as missing users.
        if (!scope.allServices() && !scope.services().contains(user.getServiceId())) {
            throw new ApiException(ResponseCode.USER_NOT_FOUND);
        }
        return user;
    }
}
