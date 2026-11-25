import type {
  AdminUser,
  AdminUserDetail,
  ApiEnvelope,
  Assessment,
  Concert,
  AssessmentAttempt,
  BookingHistory,
  ConcertDetail,
  ConcertTrend,
  ConcertUser,
  CreditHistoryItem,
  DashboardSummary,
  PageResponse,
  UserEvent,
  UserFeatures,
} from './types'

export class ApiError extends Error {
  status: number
  code: string
  constructor(message: string, status: number, code = 'REQUEST_FAILED') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

type Params = Record<string, string | number | undefined>

/** 모든 화면은 관리자 키 하나로 /api/v1/admin/* 를 호출합니다. */
async function request<T>(path: string, adminKey: string, params?: Params): Promise<T> {
  const url = new URL(path, window.location.origin)
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== '') url.searchParams.set(key, String(value))
  })

  let response: Response
  try {
    response = await fetch(url.toString(), {
      method: 'GET',
      headers: { 'X-API-Key': adminKey, Accept: 'application/json' },
    })
  } catch {
    throw new ApiError('백엔드에 연결할 수 없습니다. 서버 실행 및 주소를 확인해 주세요.', 0, 'NETWORK_ERROR')
  }

  const body = (await response.json().catch(() => null)) as ApiEnvelope<T> | null
  if (!response.ok || !body?.success) {
    throw new ApiError(body?.message || `요청에 실패했습니다. (HTTP ${response.status})`, response.status, body?.code)
  }
  return body.data
}

const id = encodeURIComponent

export const api = {
  summary: (key: string, serviceId: string) =>
    request<DashboardSummary>('/api/v1/admin/summary', key, { serviceId }),

  concerts: (key: string, serviceId: string, query: Params = {}) =>
    request<PageResponse<Concert>>('/api/v1/admin/concerts', key, { serviceId, ...query }),

  concert: (key: string, serviceId: string, concertId: string) =>
    request<ConcertDetail>(`/api/v1/admin/concerts/${id(concertId)}`, key, { serviceId }),

  /** from · to는 UTC 오프셋이 붙은 ISO 문자열, intervalSeconds는 60 · 300 · 600 · 3600. 버킷은 최대 500개입니다. */
  concertTrends: (key: string, serviceId: string, concertId: string, query: Params = {}) =>
    request<ConcertTrend>(`/api/v1/admin/concerts/${id(concertId)}/trends`, key, { serviceId, ...query }),

  concertUsers: (key: string, serviceId: string, concertId: string, query: Params = {}) =>
    request<PageResponse<ConcertUser>>(`/api/v1/admin/concerts/${id(concertId)}/users`, key, { serviceId, ...query }),

  users: (key: string, query: Params) => request<PageResponse<AdminUser>>('/api/v1/admin/users', key, query),

  user: (key: string, userId: string) => request<AdminUserDetail>(`/api/v1/admin/users/${id(userId)}`, key),

  userEvents: (key: string, userId: string, query: Params = {}) =>
    request<PageResponse<UserEvent>>(`/api/v1/admin/users/${id(userId)}/events`, key, {
      page: 0,
      size: 50,
      sort: 'occurredAt,desc',
      ...query,
    }),

  userFeatures: (key: string, userId: string) =>
    request<UserFeatures>(`/api/v1/admin/users/${id(userId)}/features`, key),

  userAssessments: (key: string, userId: string, query: Params = {}) =>
    request<PageResponse<Assessment>>(`/api/v1/admin/users/${id(userId)}/assessments`, key, {
      page: 0,
      size: 10,
      sort: 'assessedAt,desc',
      ...query,
    }),
  /** 성공한 평가뿐 아니라 데이터 부족 · INVALID로 끝난 시도도 최신순으로 돌려줍니다. */
  userAssessmentAttempts: (key: string, userId: string, query: Params = {}) =>
    request<PageResponse<AssessmentAttempt>>(`/api/v1/admin/users/${id(userId)}/assessment-attempts`, key, {
      page: 0,
      size: 10,
      ...query,
    }),

  /** 티켓 단위 관람 이력(예매 · 관람 완료 · 노쇼 · 취소). */
  userBookings: (key: string, userId: string, query: Params = {}) =>
    request<PageResponse<BookingHistory>>(`/api/v1/admin/users/${id(userId)}/bookings`, key, {
      page: 0,
      size: 20,
      ...query,
    }),

  /** Credit 변경 이력과 산출 근거(최신순). */
  userCreditHistory: (key: string, userId: string, query: Params = {}) =>
    request<PageResponse<CreditHistoryItem>>(`/api/v1/admin/users/${id(userId)}/credit-history`, key, {
      page: 0,
      size: 10,
      ...query,
    }),
}
