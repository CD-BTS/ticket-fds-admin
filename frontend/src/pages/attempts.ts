import type { UserEvent } from '../api/types'

export type AttemptResult = 'SUCCESS' | 'FAILED' | 'IN_PROGRESS'

export interface Attempt {
  attemptId: string
  concertId: string
  result: AttemptResult
  stage: string
  status: string
  lastAt: string
}

export const STAGE_LABEL: Record<string, string> = {
  STARTED: '진입', CAPTCHA: '캡차 인증', QUEUE: '대기열', SEAT_SELECTION: '좌석 선택',
  SEAT_HOLD: '좌석 점유', PAYMENT: '결제', COMPLETED: '예매 완료',
}
export const STATUS_LABEL: Record<string, string> = {
  ENTERED: '진행', SUCCEEDED: '성공', REJECTED: '거절', FAILED: '실패', ABANDONED: '이탈', EXPIRED: '만료',
}

const text = (v: unknown) => (typeof v === 'string' ? v : '')
const seq = (v: unknown) => (typeof v === 'number' ? v : Number(v) || 0)

/**
 * BOOKING_ATTEMPT 이벤트를 attemptId별로 묶어 시도 한 건의 최종 상태를 계산합니다.
 * 한 번의 시도는 단계마다 이벤트가 생기므로 sequence가 가장 큰 이벤트가 최신 상태입니다.
 */
export function groupAttempts(events: UserEvent[]): Attempt[] {
  const byId = new Map<string, { last: UserEvent; succeeded: boolean }>()
  for (const e of events) {
    if (e.eventType !== 'BOOKING_ATTEMPT' || !e.metadata) continue
    const id = text(e.metadata.attemptId)
    if (!id) continue
    const done = text(e.metadata.stage) === 'COMPLETED' && text(e.metadata.status) === 'SUCCEEDED'
    const cur = byId.get(id)
    if (!cur) { byId.set(id, { last: e, succeeded: done }); continue }
    if (seq(e.metadata.sequence) > seq(cur.last.metadata?.sequence)) cur.last = e
    cur.succeeded = cur.succeeded || done
  }
  const list: Attempt[] = []
  for (const [attemptId, { last, succeeded }] of byId) {
    const status = text(last.metadata?.status)
    const result: AttemptResult = succeeded ? 'SUCCESS'
      : ['REJECTED', 'FAILED', 'ABANDONED', 'EXPIRED'].includes(status) ? 'FAILED' : 'IN_PROGRESS'
    list.push({
      attemptId, concertId: text(last.metadata?.concertId), result,
      stage: text(last.metadata?.stage), status, lastAt: last.occurredAt,
    })
  }
  return list.sort((a, b) => new Date(b.lastAt).getTime() - new Date(a.lastAt).getTime())
}
