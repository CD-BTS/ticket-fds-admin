import type { UserEvent } from '../api/types'

export type AttemptResult = 'SUCCESS' | 'FAILED' | 'IN_PROGRESS'

export interface Attempt {
  attemptId: string
  concertId: string
  result: AttemptResult
  stage: string
  status: string
  /** 이 시도에서 가장 이른 이벤트 시각(조회된 이벤트 범위 안에서) */
  startedAt: string
  lastAt: string
  /** 이 시도에 속한 단계 이벤트(진행 순서대로) */
  events: UserEvent[]
}

export const STAGE_LABEL: Record<string, string> = {
  STARTED: '진입', CAPTCHA: 'CAPTCHA 인증', QUEUE: '대기열', SEAT_SELECTION: '좌석 선택',
  SEAT_HOLD: '좌석 선점', PAYMENT: '결제', COMPLETED: '예매 완료',
}
export const STATUS_LABEL: Record<string, string> = {
  ENTERED: '진행', SUCCEEDED: '성공', REJECTED: '거절', FAILED: '실패', ABANDONED: '이탈', EXPIRED: '만료',
}
export const FAILED_STATUSES = ['REJECTED', 'FAILED', 'ABANDONED', 'EXPIRED']

const text = (v: unknown) => (typeof v === 'string' ? v : '')
const seq = (v: unknown) => (typeof v === 'number' ? v : Number(v) || 0)
export const eventStage = (e: UserEvent) => text(e.metadata?.stage)
export const eventStatus = (e: UserEvent) => text(e.metadata?.status)

/**
 * 예매 시도 단계 이벤트를 시도(attemptId)별로 묶어 시도 한 건의 최종 상태를 계산합니다.
 * 한 번의 시도는 단계마다 이벤트가 생기므로 sequence가 가장 큰 이벤트가 최신 상태입니다.
 */
export function groupAttempts(events: UserEvent[]): Attempt[] {
  const byId = new Map<string, { events: UserEvent[]; succeeded: boolean }>()
  for (const e of events) {
    if (e.eventType !== 'BOOKING_ATTEMPT' || !e.metadata) continue
    const id = text(e.metadata.attemptId)
    if (!id) continue
    const done = eventStage(e) === 'COMPLETED' && eventStatus(e) === 'SUCCEEDED'
    const cur = byId.get(id)
    if (!cur) byId.set(id, { events: [e], succeeded: done })
    else { cur.events.push(e); cur.succeeded = cur.succeeded || done }
  }
  const list: Attempt[] = []
  for (const [attemptId, { events: own, succeeded }] of byId) {
    own.sort((a, b) => seq(a.metadata?.sequence) - seq(b.metadata?.sequence) || new Date(a.occurredAt).getTime() - new Date(b.occurredAt).getTime())
    const last = own[own.length - 1]
    const status = eventStatus(last)
    const result: AttemptResult = succeeded ? 'SUCCESS' : FAILED_STATUSES.includes(status) ? 'FAILED' : 'IN_PROGRESS'
    const startedAt = own.reduce((min, e) => (new Date(e.occurredAt).getTime() < new Date(min).getTime() ? e.occurredAt : min), own[0].occurredAt)
    list.push({ attemptId, concertId: text(last.metadata?.concertId), result, stage: eventStage(last), status, startedAt, lastAt: last.occurredAt, events: own })
  }
  return list.sort((a, b) => new Date(b.lastAt).getTime() - new Date(a.lastAt).getTime())
}
