package com.bts.fairticket.admin.dto;

import com.bts.fairticket.policy.dto.PolicyDecisionResponse;
import com.bts.fairticket.user.dto.UserDetailResponse;

/**
 * 서로 다른 개념을 구분해서 담는다 (#67).
 * <ul>
 *   <li>user.anomalyScore: 가장 최근에 성공한 AI 평가의 점수(없으면 null). 0.5 이상이 AI 이상탐지다.</li>
 *   <li>user.tier: Credit 등급. AI 이상탐지 여부나 정책과는 별개다.</li>
 *   <li>latestPolicy: 외부 서비스의 요청으로 실제로 내려진 마지막 정책 결정(없으면 null).</li>
 *   <li>expectedPolicy: 현재 Credit과 최신 점수로 지금 요청하면 받을 조치. 조회용 계산이며 기록을 만들지 않는다.</li>
 *   <li>assessmentStatus · latestAttempt: 가장 최근 평가 시도의 상태와 내용(NOT_EVALUATED면 latestAttempt는 null).</li>
 *   <li>dynamicPenalty · latestCalculation: 현재 점수에 적용된 동적 감점과 그 산출 근거(#67 이전이면 null).</li>
 * </ul>
 */
public record AdminUserDetailResponse(UserDetailResponse user, PolicyDecisionResponse latestPolicy,
        String assessmentStatus, AssessmentAttemptResponse latestAttempt, String expectedPolicy,
        Double dynamicPenalty, CreditCalculationResponse latestCalculation) { }
