import { ApiError } from './api/client'

export interface Session {
  serviceId: string
  adminKey: string
}

export const number = (value: number | null | undefined) =>
  value == null ? '—' : new Intl.NumberFormat('ko-KR').format(value)

export const score = (value: number | null | undefined) => (value == null ? '—' : value.toFixed(2))

const fmt = (parsed: Date, raw: string) =>
  Number.isNaN(parsed.getTime())
    ? raw.replace('T', ' ').slice(0, 19)
    : new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(parsed)

/** 서버가 타임존 없이 내려주는 시각(사용자·평가)은 변환하지 않고 그대로 보여줍니다. */
export const dateTime = (value?: string | null) => (value ? fmt(new Date(value), value) : '—')

/** 오프셋이 붙은 시각(공연·이벤트·정책)은 브라우저 시간대로 보여줍니다. */
export const dateTimeUtc = (value?: string | null) => {
  if (!value) return '—'
  const iso = /[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : `${value}Z`
  return fmt(new Date(iso), value)
}

export const explainError = (error: unknown) =>
  error instanceof ApiError ? `${error.message} (${error.code})` : '요청 중 오류가 발생했습니다.'

/** 사용자 UUID 형태면 userId, 아니면 외부 사용자 ID(이름)로 검색합니다. */
export const looksLikeUserId = (text: string) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(text)
