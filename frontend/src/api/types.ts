export interface ApiEnvelope<T> {
  success: boolean
  code: string
  data: T
  message: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export interface DashboardSummary {
  totalUsers: number
  activeUsers: number
  suspendedUsers: number
  usersWithCredit: number
  averageCreditScore: number | null
  usersByTier: Record<string, number>
  totalEvents: number
  eventsByType: Record<string, number>
  // #67: AI 평가 · 예상 정책 · 실제 제재. 사용자 수는 모두 고유 사용자입니다.
  assessedUsers: number
  unassessedUsers: number
  anomalyUsers: number
  insufficientDataUsers: number
  usersByTierRatio: Record<string, number>
  usersByExpectedPolicy: Record<string, number>
  expectedSanctionUsers: number
  sanctions: SanctionStatistics
}

/**
 * 외부 서비스가 실제로 적용했다고 보고한 제재. recorded가 false면 "제재 없음"이 아니라 "기록 없음"이며
 * 나머지 값은 모두 null입니다. applicationCount는 고유 정책 결정 수입니다.
 */
export interface SanctionStatistics {
  recorded: boolean
  appliedUserCount: number | null
  byPolicy: Record<string, { userCount: number; applicationCount: number }> | null
  captcha: { challenged: number; passed: number; failed: number } | null
}

/** 가장 최근 평가 시도의 상태. NOT_EVALUATED는 한 번도 평가되지 않은 사용자입니다. */
export type AssessmentStatus = 'OK' | 'INSUFFICIENT_DATA' | 'INVALID' | 'NOT_EVALUATED' | string

/** GET /api/v1/admin/users, 공연별 사용자의 user 필드. LocalDateTime은 타임존 없이 내려옵니다. */
export interface AdminUser {
  userId: string
  serviceId: string
  externalUserId: string
  accountStatus: 'ACTIVE' | 'SUSPENDED' | string
  createdAt: string
  creditScore: number | null
  tier: string | null
  anomalyScore: number | null
  lastAssessedAt: string | null
  /** anomalyScore는 최신 "성공" 평가의 점수이고, 이 값은 최신 평가 "시도"의 상태입니다. */
  assessmentStatus: AssessmentStatus
}

export interface UserDetail extends Omit<AdminUser, 'assessmentStatus'> {
  latestAction: string | null
}

export interface PolicyDecision {
  decisionId: number
  userId: string
  context: string
  creditScore: number
  anomalyScore: number
  action: string
  reason: string
  decidedAt: string
  concertId: string | null
}

/** AI 평가 요청에 실제로 넣은 Feature 6개. 계산할 수 없어 null로 보낸 값은 null입니다(0이 아님). */
export interface AssessmentFeatures {
  bookingCount: number | null
  avgBookingInterval: number | null
  bookingIntervalVariance: number | null
  cancelRate: number | null
  cardReuseCount: number | null
  ipBookingDensity: number | null
}

/** GET /api/v1/admin/users/{userId}/assessment-attempts. OK가 아니면 anomalyScore는 null입니다. 시각은 UTC(Z). */
export interface AssessmentAttempt {
  attemptId: number
  status: AssessmentStatus
  reason: string | null
  modelVersion: string | null
  calculatedAt: string
  createdAt: string
  assessmentId: number | null
  anomalyScore: number | null
  features: AssessmentFeatures | null
}

/**
 * Credit 산출 근거. 계산 당시 저장된 값입니다.
 * baseCredit - dynamicPenalty = rawCredit → 0~100 제한 후 반올림 → scoreAfter.
 * kind가 ATTENDANCE(관람 이력 변경)이면 감점 상세는 null이고 sourceCalculationId가 원본 평가를 가리킵니다.
 */
export interface CreditCalculation {
  calculationId: number
  historyId: number | null
  kind: 'EVALUATION' | 'ATTENDANCE' | string
  assessmentId: number | null
  sourceCalculationId: number | null
  completedCount: number
  noShowCount: number
  autoCancelledCount: number
  anomalyScore: number | null
  recentIpAttemptCount: number | null
  intervalVariance: number | null
  cardSharedAccounts: number | null
  baseCredit: number
  aiPenalty: number | null
  cardPenalty: number | null
  ipPenalty: number | null
  variancePenalty: number | null
  rulePenaltyRaw: number | null
  rulePenalty: number | null
  dynamicPenalty: number
  rawCredit: number
  scoreBefore: number
  scoreAfter: number
  tier: string
  reason: string
  createdAt: string
}

/** GET /api/v1/admin/users/{userId}/credit-history. calculation은 #67 이전 이력이면 null입니다. */
export interface CreditHistoryItem {
  historyId: number
  eventId: number | null
  scoreBefore: number
  scoreAfter: number
  reason: string
  createdAt: string
  calculation: CreditCalculation | null
}

/** GET /api/v1/admin/users/{userId} */
export interface AdminUserDetail {
  user: UserDetail
  /** 외부 서비스의 요청으로 실제 내려진 마지막 정책 결정 */
  latestPolicy: PolicyDecision | null
  assessmentStatus: AssessmentStatus
  latestAttempt: AssessmentAttempt | null
  /** 현재 Credit과 최신 점수로 지금 요청하면 받을 조치(조회용 계산) */
  expectedPolicy: string
  dynamicPenalty: number | null
  latestCalculation: CreditCalculation | null
}

export interface UserEvent {
  eventId: number
  serviceId: string
  externalEventId: string
  userId: string
  eventType: string
  bookingId: string | null
  ticketId: string | null
  occurredAt: string
  metadata: Record<string, unknown> | null
}

export interface Assessment {
  assessmentId: number
  anomalyScore: number | null
  policyAction: string | null
  modelVersion: string | null
  assessedAt: string
  /** 평가 당시 AI에 보낸 Feature. #67 이전 평가는 null입니다. */
  features: AssessmentFeatures | null
}

/** 시각은 UTC 오프셋이 붙은 ISO 문자열입니다. */
export interface Concert {
  serviceId: string
  concertId: string
  name: string
  startsAt: string
  endsAt: string
  venue: string
  /** 그 공연에 예매를 시도한 사용자 중 최신 AI 이상 점수가 0.5 이상인 고유 사용자 수 (목록 응답에만 있음) */
  anomalyUserCount?: number
}

/**
 * GET /api/v1/admin/concerts/{concertId}. 값마다 단위가 다릅니다.
 * 고유 시도: bookingAttemptCount = 성공 + 실패 + 미종료. 고유 사용자: 이상탐지 · 예상 제재.
 * anomalyUserCount는 "이 공연에서 탐지된 건수"가 아니라 참여자 중 현재 이상으로 판정된 사용자 수입니다.
 */
export interface ConcertDetail {
  concert: Concert
  bookingAttemptCount: number
  bookingAttemptUserCount: number
  bookingSuccessCount: number
  bookingFailureCount: number
  bookingInProgressCount: number
  /** "단계/상태" → 고유 시도 수 */
  failureBreakdown: Record<string, number>
  bookingCount: number
  cancelCount: number
  checkInCount: number
  assessedUserCount: number
  unassessedUserCount: number
  anomalyUserCount: number
  averageCreditScore: number | null
  usersByExpectedPolicy: Record<string, number>
  /** 현재 Credit과 점수로 계산한 예상 제재 대상(고유 사용자). 실제 적용이 아닙니다. */
  expectedSanctionUserCount: number
  sanctions: SanctionStatistics
}

/** GET /api/v1/admin/concerts/{concertId}/trends. 시각은 UTC(Z)이고 빈 버킷도 0으로 들어 있습니다. */
export interface ConcertTrend {
  serviceId: string
  concertId: string
  from: string
  to: string
  intervalSeconds: number
  sanctionRecorded: boolean
  buckets: {
    startsAt: string
    attemptCount: number
    anomalyAssessmentCount: number
    sanctionAppliedCount: number
  }[]
}

export interface ConcertUser {
  user: AdminUser
  lastBookingAttemptAt: string | null
}

/** 최근 30일 행동 이벤트로 조회 시점에 계산한 AI 입력 Feature 6개. 계산하지 못한 값은 null입니다. */
export interface UserFeatures {
  userId: string
  calculatedAt: string
  windowDays: number
  bookingCount: number | null
  avgBookingInterval: number | null
  bookingIntervalVariance: number | null
  cancelRate: number | null
  cardReuseCount: number | null
  ipBookingDensity: number | null
}

/** GET /api/v1/admin/users/{userId}/bookings. 티켓 1장의 관람 이력이며 시각은 UTC(Z)입니다. */
export interface BookingHistory {
  historyId: number
  serviceId: string
  bookingId: string
  ticketId: string | null
  concertId: string | null
  concertName: string | null
  venue: string | null
  status: 'BOOKED' | 'COMPLETED' | 'NO_SHOW' | 'AUTO_CANCELLED' | 'CANCELLED' | string
  bookedAt: string | null
  performanceStartsAt: string | null
  performanceEndsAt: string | null
  createdAt: string | null
}
