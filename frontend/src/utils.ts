import { ApiError } from './api/client'

export interface Session {
  serviceId: string
  adminKey: string
}

const nf = new Intl.NumberFormat('ko-KR')

/** 천 단위 구분. 값이 없으면(null) 0이 아니라 "—"입니다. */
export const number = (value: number | null | undefined) => (value == null ? '—' : nf.format(value))

/** 소수 값. 정수면 그대로, 아니면 지정한 자릿수까지 보여줍니다. */
export const decimal = (value: number | null | undefined, digits = 2) =>
  value == null ? '—' : Number.isInteger(value) ? nf.format(value) : value.toFixed(digits)

/** AI 이상 점수(0~1). 미평가는 0이 아니라 "—"입니다. */
export const score = (value: number | null | undefined) => (value == null ? '—' : value.toFixed(3))

/** 비율 표시. 분모가 0이면 계산하지 않습니다. */
export const percent = (part: number | null | undefined, total: number | null | undefined, digits = 1) =>
  part == null || !total ? '—' : `${((part / total) * 100).toFixed(digits)}%`

const parse = (value: string) => {
  // 서버가 타임존 없이 내려주는 시각(LocalDateTime)은 UTC입니다.
  const iso = /[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : `${value}Z`
  return new Date(iso)
}
const pad = (n: number) => String(n).padStart(2, '0')

/** 모든 화면의 시각 표시 형식: YYYY-MM-DD HH:mm (브라우저 시간대). */
export const dateTime = (value?: string | null) => {
  if (!value) return '—'
  const d = parse(value)
  if (Number.isNaN(d.getTime())) return value
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
/** 초 단위까지 표시(행동 이벤트처럼 짧은 간격이 중요한 이력). */
export const dateTimeSec = (value?: string | null) => {
  if (!value) return '—'
  const d = parse(value)
  return Number.isNaN(d.getTime()) ? value : `${dateTime(value)}:${pad(d.getSeconds())}`
}
export const timeMs = (value: string) => parse(value).getTime()

/** 브라우저 시간대 표기(예: UTC+9). 집계 기준 시각 안내에 씁니다. */
export const timeZoneLabel = () => {
  const offset = -new Date().getTimezoneOffset()
  const h = Math.trunc(offset / 60)
  const m = Math.abs(offset % 60)
  return `UTC${h >= 0 ? '+' : ''}${h}${m ? `:${pad(m)}` : ''}`
}

/** 사용자에게 보여 줄 오류 문구. 서버 내부 구현은 노출하지 않습니다. */
export const explainError = (error: unknown) => {
  if (!(error instanceof ApiError)) return '요청을 처리하지 못했습니다.'
  if (error.status === 0) return '서버에 연결할 수 없습니다. 네트워크와 서버 상태를 확인해 주세요.'
  if (error.status === 401) return '인증이 만료되었거나 올바르지 않습니다.'
  if (error.status === 403) return '이 서비스의 데이터에 접근할 권한이 없습니다.'
  if (error.status === 404) return '요청한 데이터를 찾을 수 없습니다.'
  if (error.status >= 500) return '서버에서 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
  return error.message || `요청에 실패했습니다. (HTTP ${error.status})`
}
