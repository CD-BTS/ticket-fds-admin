import { useEffect, useState } from 'react'
import { AlertTriangle, LoaderCircle } from 'lucide-react'
import { api } from '../api/client'
import type { AdminUserDetail, Assessment, Concert, UserEvent, UserFeatures } from '../api/types'
import { dateTime, dateTimeUtc, explainError, number, score } from '../utils'
import type { Session } from '../utils'
import { groupAttempts, STAGE_LABEL, STATUS_LABEL } from './attempts'

interface Props {
  session: Session
  userId: string
  concerts: Concert[]
  onOpenConcert: (concertId: string) => void
  onError: (message: string) => void
}

const FEATURES: [keyof UserFeatures, string, string][] = [
  ['bookingCount', '최근 30일 고유 예매 시도 수', '회'],
  ['avgBookingInterval', '연속 시도 시작 간격 평균', '초'],
  ['bookingIntervalVariance', '연속 시도 시작 간격 분산', '초²'],
  ['cancelRate', '예매 완료 대비 취소 비율', '%'],
  ['cardReuseCount', '같은 결제수단을 쓴 최대 고유 계정 수 (본인 포함)', '명'],
  ['ipBookingDensity', '같은 IP로 예매한 최대 고유 계정 수 (본인 포함)', '명'],
]

const EVENT_PAGES = 3
const EVENT_SIZE = 100

export default function UserDetail({ session, userId, concerts, onOpenConcert, onError }: Props) {
  const [detail, setDetail] = useState<AdminUserDetail | null>(null)
  const [features, setFeatures] = useState<UserFeatures | null>(null)
  const [featuresError, setFeaturesError] = useState('')
  const [assessments, setAssessments] = useState<Assessment[]>([])
  const [events, setEvents] = useState<UserEvent[]>([])
  const [truncated, setTruncated] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    const key = session.adminKey
    setLoading(true)
    setDetail(null); setFeatures(null); setFeaturesError(''); setAssessments([]); setEvents([]); setTruncated(false)

    api.user(key, userId)
      .then(d => alive && setDetail(d))
      .catch(e => alive && onError(`사용자 조회 실패: ${explainError(e)}`))
      .finally(() => alive && setLoading(false))
    // 아래 항목은 실패해도 사용자 정보 화면 전체를 막지 않도록 따로 조회합니다.
    api.userFeatures(key, userId)
      .then(f => alive && setFeatures(f))
      .catch(e => alive && setFeaturesError(explainError(e)))
    api.userAssessments(key, userId, { size: 5 })
      .then(a => alive && setAssessments(a.content))
      .catch(() => undefined)
    ;(async () => {
      const all: UserEvent[] = []
      for (let p = 0; p < EVENT_PAGES; p++) {
        const page = await api.userEvents(key, userId, { eventType: 'BOOKING_ATTEMPT', page: p, size: EVENT_SIZE })
        all.push(...page.content)
        if (!page.hasNext) return alive && setEvents(all)
      }
      if (alive) { setEvents(all); setTruncated(true) }
    })().catch(() => undefined)
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.adminKey, userId])

  if (loading) return <p className="state-line"><LoaderCircle className="spin" size={18} /> 사용자 정보를 불러오는 중입니다.</p>
  const user = detail?.user
  if (!user) return <p className="empty-line">사용자 정보를 불러오지 못했습니다. 관리자 키의 서비스 범위와 사용자 ID를 확인해 주세요.</p>
  const policy = detail?.latestPolicy

  const attempts = groupAttempts(events)
  const concertById = new Map(concerts.map(c => [c.concertId, c]))

  const featureValue = (key: keyof UserFeatures, unit: string) => {
    const v = features?.[key]
    if (typeof v !== 'number') return <span className="na">계산 불가</span>
    if (key === 'cancelRate') return `${(v * 100).toFixed(1)}%`
    return `${Number.isInteger(v) ? number(v) : v.toFixed(2)}${unit}`
  }

  return (
    <section className="page user-detail">
      <h1 className="section-label big">사용자 조회</h1>
      <div className="summary-card">
        <div className="who"><b>{user.externalUserId}</b><small className="mono">{user.userId}</small>
          <span className={`pill ${user.accountStatus === 'ACTIVE' ? 'ok' : 'off'}`}>{user.accountStatus}</span></div>
        <div className="metric"><span>등급</span><b>{user.tier ?? '—'}</b></div>
        <div className="metric"><span>크레딧</span><b>{number(user.creditScore)}</b></div>
        <div className="metric"><span>이상 점수</span><b className={user.anomalyScore != null && user.anomalyScore >= 0.7 ? 'danger' : ''}>{score(user.anomalyScore)}</b></div>
      </div>

      <h2 className="section-label">크레딧</h2>
      <div className="block">
        <p className="block-main">현재 점수 <b>{number(user.creditScore)}</b> · 등급 <b>{user.tier ?? '—'}</b></p>
        <p className="block-note"><AlertTriangle size={13} /> 점수가 어떻게 계산됐는지(Credit 변경 이력)는 관리자용 조회 API가 없어 아직 표시할 수 없습니다.</p>
      </div>

      <h2 className="section-label">이상 점수</h2>
      <div className="block">
        <p className="block-main">현재 이상 점수 <b>{score(user.anomalyScore)}</b>
          <span className="sub">최근 평가 {dateTime(user.lastAssessedAt)}</span></p>
        {policy && <p className="block-sub">최근 조치 <b>{policy.action}</b> · {policy.reason} <span className="sub">({dateTimeUtc(policy.decidedAt)})</span></p>}
        <div className="feature-head"><b>Feature별 계산값</b>
          {features ? <span>최근 {features.windowDays}일 기준 · 계산 {dateTimeUtc(features.calculatedAt)}</span>
            : featuresError ? <span className="danger">조회 실패: {featuresError}</span> : <span>계산 중…</span>}</div>
        <table className="feature-table">
          <tbody>{FEATURES.map(([key, label, unit]) => (
            <tr key={key}><td><code>{key}</code></td><td>{label}</td><td className="right">{featureValue(key, unit)}</td></tr>
          ))}</tbody>
        </table>
        {assessments.length > 0 && (
          <details className="assess"><summary>평가 이력 {assessments.length}건 보기</summary>
            <table><thead><tr><th>평가 시각</th><th>이상 점수</th><th>연결된 조치</th><th>모델</th></tr></thead>
              <tbody>{assessments.map(a => <tr key={a.assessmentId}><td>{dateTime(a.assessedAt)}</td><td>{score(a.anomalyScore)}</td><td>{a.policyAction ?? '—'}</td><td className="mono">{a.modelVersion ?? '—'}</td></tr>)}</tbody></table>
          </details>
        )}
      </div>

      <h2 className="section-label big">예매 목록</h2>
      <div className="table-card">
        <div className="table-scroll">
          <table>
            <thead><tr><th>공연 이름</th><th>공연 일시</th><th>예매</th><th>최종 단계</th><th /></tr></thead>
            <tbody>
              {attempts.map(a => {
                const c = concertById.get(a.concertId)
                return (
                  <tr key={a.attemptId}>
                    <td>{c?.name ?? (a.concertId || '—')}</td>
                    <td>{c ? dateTimeUtc(c.startsAt) : '—'}</td>
                    <td><span className={`pill ${a.result === 'SUCCESS' ? 'ok' : a.result === 'FAILED' ? 'bad' : 'wait'}`}>
                      {a.result === 'SUCCESS' ? '성공' : a.result === 'FAILED' ? '실패' : '진행 중'}</span></td>
                    <td>{STAGE_LABEL[a.stage] ?? a.stage}<small>{STATUS_LABEL[a.status] ?? a.status}</small></td>
                    <td className="right"><button className="btn outline small" disabled={!a.concertId} onClick={() => onOpenConcert(a.concertId)}>조회</button></td>
                  </tr>
                )
              })}
              {!attempts.length && <tr><td colSpan={5} className="empty-line">예매 시도 이력이 없습니다.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="pager"><span>예매 시도 {attempts.length}건{truncated && ` · 최근 이벤트 ${EVENT_PAGES * EVENT_SIZE}건까지만 집계`}</span></div>
      </div>
    </section>
  )
}
