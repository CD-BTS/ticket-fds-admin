import type {
  AdminUser,
  AdminUserDetail,
  ApiEnvelope,
  Assessment,
  Concert,
  ConcertDetail,
  ConcertUser,
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
  /**
   * 명세서의 경로는 /api/v1/admin/summary 입니다. 아직 이름을 바꾸지 않은 백엔드(/api/v1/dashboard/summary)도
   * 로그인할 수 있도록 404일 때만 이전 경로로 한 번 더 시도합니다.
   */
  summary: async (key: string, serviceId: string) => {
    try {
      return await request<DashboardSummary>('/api/v1/admin/summary', key, { serviceId })
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        return request<DashboardSummary>('/api/v1/dashboard/summary', key, { serviceId })
      }
      throw e
    }
  },

  concerts: (key: string, serviceId: string, query: Params = {}) =>
    request<PageResponse<Concert>>('/api/v1/admin/concerts', key, { serviceId, ...query }),

  concert: (key: string, serviceId: string, concertId: string) =>
    request<ConcertDetail>(`/api/v1/admin/concerts/${id(concertId)}`, key, { serviceId }),

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
}
