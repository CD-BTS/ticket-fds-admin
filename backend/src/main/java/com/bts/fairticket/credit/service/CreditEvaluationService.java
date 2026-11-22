package com.bts.fairticket.credit.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.behavior.entity.BehaviorEvent;
import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.dto.CreditEvaluationContext;
import com.bts.fairticket.credit.entity.CreditHistory;
import com.bts.fairticket.credit.entity.UserCard;
import com.bts.fairticket.credit.entity.UserCredit;
import com.bts.fairticket.credit.policy.CreditCalculator;
import com.bts.fairticket.credit.policy.CreditCalculator.CalculationInput;
import com.bts.fairticket.credit.policy.CreditCalculator.CalculationResult;
import com.bts.fairticket.credit.policy.CreditPolicyConstants;
import com.bts.fairticket.credit.repository.BookingHistoryRepository;
import com.bts.fairticket.credit.repository.CreditHistoryRepository;
import com.bts.fairticket.credit.repository.UserCardRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.user.entity.UserAccount;
import com.bts.fairticket.user.repository.UserAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditEvaluationService {

    private final UserAccountRepository userAccountRepository;
    private final UserCreditRepository userCreditRepository;
    private final BookingHistoryRepository bookingHistoryRepository;
    private final UserCardRepository userCardRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final CreditHistoryRepository creditHistoryRepository;
    private final CreditCalculator creditCalculator;

    @Transactional
    public EvaluationSummary evaluateAndApplyCredit(String userId, CreditEvaluationContext context) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.USER_NOT_FOUND));

        UserCredit userCredit = userCreditRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.CREDIT_NOT_FOUND));

        int oldScore = userCredit.getCreditScore();

        // 1. 관람 이력 통계 집계 (A, N, C_auto)
        var bookingStats = bookingHistoryRepository.getBookingStatsByUserId(userId);
        long completedCount = (bookingStats != null && bookingStats.getCompletedCount() != null) 
                ? bookingStats.getCompletedCount() : 0L;
        long noShowCount = (bookingStats != null && bookingStats.getNoShowCount() != null) 
                ? bookingStats.getNoShowCount() : 0L;
        long autoCancelledCount = (bookingStats != null && bookingStats.getAutoCancelledCount() != null) 
                ? bookingStats.getAutoCancelledCount() : 0L;

        // 2. 최신 AI 이상도 조회
        Optional<RiskAssessment> latestRisk =
                riskAssessmentRepository.findFirstByUser_UserIdOrderByAssessedAtDescAssessmentIdDesc(userId);
        Double anomalyScore = latestRisk.map(RiskAssessment::getAnomalyScore).orElse(null);

        // 3. 룰 기반 감점 계산 (카드 공유, IP 집중도, 요청 분산)
        double rulePenalty = calculateRulePenalty(userId, context);

        // 4. 수식 계산 컴포넌트 호출
        CalculationInput input = new CalculationInput(
                completedCount,
                noShowCount,
                autoCancelledCount,
                anomalyScore,
                rulePenalty
        );
        CalculationResult result = creditCalculator.calculate(input);

        int newScore = result.finalCreditScore();
        int changeAmount = newScore - oldScore;

        // 5. UserCredit 갱신. 적용한 DynamicPenalty를 함께 저장해 관람 이력 자동 갱신이 같은 감점을 쓰게 한다.
        userCredit.updateCreditScore(newScore, result.grade().name(), result.dynamicPenalty());
        userCreditRepository.save(userCredit);

        // 6. CreditHistory 이력 적재
        String reason = (context != null && context.triggerReason() != null) 
                ? context.triggerReason() 
                : "Credit 정기/동적 재산출";

        CreditHistory history = new CreditHistory(
                user,
                oldScore,  // scoreBefore
                newScore,  // scoreAfter
                reason
        );
        creditHistoryRepository.save(history);

        log.info("User {} credit evaluated: {} -> {} (change: {}), grade: {}", 
                userId, oldScore, newScore, changeAmount, result.grade());

        return new EvaluationSummary(
                userId,
                oldScore,
                newScore,
                changeAmount,
                result.grade().name(),
                result.baseCredit(),
                result.dynamicPenalty(),
                result.penaltyPolicy()
        );
    }

    /**
     * 관람 이력(COMPLETED / NO_SHOW)이 바뀌었을 때 Credit을 다시 계산한다.
     * 호출자의 트랜잭션에 참여하며, 호출자는 실제로 상태가 바뀐 경우에만 한 번 호출한다.
     *
     * AI·Rule 감점은 새로 계산하지 않고 마지막 평가에서 저장한 DynamicPenalty를 그대로 쓴다.
     * 그래서 같은 관람 이력과 같은 감점이면 수동 평가(evaluateAndApplyCredit)와 결과가 같다.
     *
     * 저장된 감점이 없는 사용자(#56 이전 행):
     *  - 평가 이력(credit_history)이 없으면 감점을 받은 적이 없으므로 0으로 계산한다.
     *  - 평가 이력이 있으면 당시 감점을 알 수 없다. 현재 점수에서 역산하지 않고 점수를 그대로 둔다.
     *    다음 수동 평가가 감점을 저장하면서 그동안의 관람 이력까지 반영한다.
     *
     * @return 점수를 다시 계산했으면 결과, 보류했으면 empty
     */
    @Transactional
    public Optional<EvaluationSummary> applyAttendanceChange(UserAccount user, BehaviorEvent event, String reason) {
        String userId = user.getUserId();
        Optional<UserCredit> found = userCreditRepository.findById(userId);
        if (found.isEmpty()) {
            log.warn("User {} has no user_credit row; attendance change is not reflected in credit", userId);
            return Optional.empty();
        }
        UserCredit userCredit = found.get();

        Double storedPenalty = userCredit.getDynamicPenalty();
        if (storedPenalty == null) {
            if (creditHistoryRepository.existsByUser_UserId(userId)) {
                log.warn("User {} was evaluated before penalties were stored; credit is left unchanged "
                        + "until the next evaluation", userId);
                return Optional.empty();
            }
            storedPenalty = 0.0;
        }

        int oldScore = userCredit.getCreditScore();
        var bookingStats = bookingHistoryRepository.getBookingStatsByUserId(userId);
        long completedCount = (bookingStats != null && bookingStats.getCompletedCount() != null)
                ? bookingStats.getCompletedCount() : 0L;
        long noShowCount = (bookingStats != null && bookingStats.getNoShowCount() != null)
                ? bookingStats.getNoShowCount() : 0L;
        long autoCancelledCount = (bookingStats != null && bookingStats.getAutoCancelledCount() != null)
                ? bookingStats.getAutoCancelledCount() : 0L;

        CalculationResult result = creditCalculator.calculateWithPenalty(
                completedCount, noShowCount, autoCancelledCount, storedPenalty);
        int newScore = result.finalCreditScore();

        userCredit.updateCreditScore(newScore, result.grade().name(), result.dynamicPenalty());
        userCreditRepository.save(userCredit);
        creditHistoryRepository.save(new CreditHistory(user, event, oldScore, newScore, reason));

        log.info("User {} credit updated by attendance change: {} -> {}, grade: {}",
                userId, oldScore, newScore, result.grade());

        return Optional.of(new EvaluationSummary(userId, oldScore, newScore, newScore - oldScore,
                result.grade().name(), result.baseCredit(), result.dynamicPenalty(), result.penaltyPolicy()));
    }

    private double calculateRulePenalty(String userId, CreditEvaluationContext context) {
        double penalty = 0.0;

        // ① 카드 공유 감점 (사용자가 등록한 카드 중 최대 공유 계정 수 기준)
        List<UserCard> cards = userCardRepository.findByUser_UserId(userId);
        long maxSharedAccounts = 0;
        for (UserCard card : cards) {
            long count = userCardRepository.countDistinctUserByCardHash(card.getCardHash());
            if (count > maxSharedAccounts) {
                maxSharedAccounts = count;
            }
        }

        if (maxSharedAccounts >= 4) {
            penalty += CreditPolicyConstants.PENALTY_CARD_SHARING_HIGH; // -15
        } else if (maxSharedAccounts >= 2) {
            penalty += CreditPolicyConstants.PENALTY_CARD_SHARING_MID;  // -5
        }

        // ② IP 집중도 감점 (최근 10분간 5회 이상 시도)
        if (context != null && context.recentIpAttemptCount() != null && context.recentIpAttemptCount() >= 5) {
            penalty += CreditPolicyConstants.PENALTY_IP_BURST; // -5
        }

        // ③ 요청 간격 분산 감점 (1.0초^2 미만)
        if (context != null && context.intervalVariance() != null && context.intervalVariance() < 1.0) {
            penalty += CreditPolicyConstants.PENALTY_LOW_INTERVAL_VARIANCE; // -5
        }

        return Math.min(CreditPolicyConstants.MAX_RULE_PENALTY, penalty);
    }

    public record EvaluationSummary(
            String userId,
            int oldScore,
            int newScore,
            int changeAmount,
            String grade,
            double baseCredit,
            double dynamicPenalty,
            String penaltyPolicy
    ) {}
}