import { useEffect, useState } from 'react'
import { LoaderCircle } from 'lucide-react'
import { api } from '../api/client'
import type {
  AdminUserDetail, Assessment, AssessmentAttempt, AssessmentFeatures, Concert, CreditCalculation,
  CreditHistoryItem, UserEvent, UserFeatures,
} from '../api/types'
import { dateTimeUtc, explainError, number, score } from '../utils'
import type { Session } from '../utils'
import { groupAttempts, STAGE_LABEL, STATUS_LABEL } from './attempts'

interface Props {
  session: Session
  userId: string
  concerts: Concert[]
  onOpenConcert: (concertId: string) => void
  onError: (message: string) => void
}

const FEATURES: [keyof AssessmentFeatures, string, string][] = [
  ['bookingCount', '최근 30일 고유 예매 시도 수', '회'],
  ['avgBookingInterval', '연속 시도 시작 간격 평균', '초'],
  ['bookingIntervalVariance', '연속 시도 시작 간격 분산', '초²'],
  ['cancelRate', '예매 완료 대비 취소 비율', '%'],
  ['cardReuseCount', '같은 결제수단을 쓴 최대 고유 계정 수 (본인 포함)', '명'],
  ['ipBookingDensity', '같은 IP로 예매한 최대 고유 계정 수 (본인 포함)', '명'],
]

const STATUS_TEXT: Record<string, string> = {
  OK: '평가 완료', INSUFFICIENT_DATA: '데이터 부족', INVALID: '평가 불가(입력 오류)', NOT_EVALUATED: '미평가',
}
const STATUS_PILL: Record<string, string> = { OK: 'ok', INSUFFICIENT_DATA: 'wait', INVALID: 'bad', NOT_EVALUATED: 'off' }
const POLICY_TEXT: Record<string, string> = {
  ALLOW: '허용', REQUIRE_CAPTCHA: 'CAPTCHA 인증', DELAY: '대기열 후순위', RESTRICT: '예매 제한',
}
const POLICY_PILL: Record<string, string> = { ALLOW: 'ok', REQUIRE_CAPTCHA: 'wait', DELAY: 'wait', RESTRICT: 'bad' }
const KIND_TEXT: Record<string, string> = { EVALUATION: '평가(감점 계산)', ATTENDANCE: '관람 이력 반영' }

const EVENT_PAGES = 3
const EVENT_SIZE = 100
const HISTORY_SIZE = 10

/** 점수 · 감점 값. 기록이 없으면(null) 0이 아니라 "—"입니다. */
const points = (value: number | null | undefined) =>
  value == null ? '—' : (Number.isInteger(value) ? String(value) : value.toFixed(2))

export default function UserDetail({ session, userId, concerts, onOpenConcert, onError }: Props) {
  const [detail, setDetail] = useState<AdminUserDetail | null>(null)
  const [features, setFeatures] = useState<UserFeatures | null>(null)
  const [featuresError, setFeaturesError] = useState('')
  const [assessments, setAssessments] = useState<Assessment[]>([])
  const [attemptHistory, setAttemptHistory] = useState<AssessmentAttempt[] | null>(null)
  const [attemptError, setAttemptError] = useState('')
  const [history, setHistory] = useState<CreditHistoryItem[] | null>(null)
  const [historyTotal, setHistoryTotal] = useState(0)
  const [historyError, setHistoryError] = useState('')
  const [events, setEvents] = useState<UserEvent[]>([])
  const [truncated, setTruncated] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    const key = session.adminKey
    setLoading(true)
    setDetail(null); setFeatures(null); setFeaturesError(''); setAssessments([]); setEvents([]); setTruncated(false)
    setAttemptHistory(null); setAttemptError(''); setHistory(null); setHistoryTotal(0); setHistoryError('')

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
    api.userAssessmentAttempts(key, userId, { size: HISTORY_SIZE })
      .then(a => alive && setAttemptHistory(a.content))
      .catch(e => alive && setAttemptError(explainError(e)))
    api.userCreditHistory(key, userId, { size: HISTORY_SIZE })
      .then(h => { if (alive) { setHistory(h.content); setHistoryTotal(h.totalElements) } })
      .catch(e => alive && setHistoryError(explainError(e)))
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
  if (!detail || !user) return <p className="empty-line">사용자 정보를 불러오지 못했습니다. 관리자 키의 서비스 범위와 사용자 ID를 확인해 주세요.</p>
  const policy = detail.latestPolicy
  const calculation = detail.latestCalculation
  const latestAttempt = detail.latestAttempt
  const status = detail.assessmentStatus
  // 최신 성공 평가(점수가 나온 평가)에서 AI에 보낸 Feature. #67 이전 평가는 기록이 없습니다.
  const assessedFeatures = assessments[0]?.features ?? null

  const attempts = groupAttempts(events)
  const concertById = new Map(concerts.map(c => [c.concertId, c]))

  const featureValue = (source: AssessmentFeatures | UserFeatures | null, key: keyof AssessmentFeatures, unit: string) => {
    if (!source) return <span className="na">기록 없음</span>
    const v = source[key]
    if (typeof v !== 'number') return <span className="na">계산 불가</span>
    if (key === 'cancelRate') return `${(v * 100).toFixed(1)}%`
    return `${Number.isInteger(v) ? number(v) : v.toFixed(2)}${unit}`
  }

  const calculationRows = (c: CreditCalculation): [string, string, string][] => [
    ['기본 Credit', points(c.baseCredit), `관람 완료 ${c.completedCount} · 노쇼 ${c.noShowCount} · 자동 취소 ${c.autoCancelledCount}`],
    ['AI 감점', points(c.aiPenalty), c.aiPenalty == null ? '이번에 새로 계산하지 않음' : `이상 점수 ${score(c.anomalyScore)}`],
    ['카드 공유 감점', points(c.cardPenalty), c.cardPenalty == null ? '이번에 새로 계산하지 않음'
      : `같은 카드 계정 ${c.cardSharedAccounts ?? '—'}개`],
    ['IP 집중도 감점', points(c.ipPenalty), c.ipPenalty == null ? '이번에 새로 계산하지 않음'
      : c.recentIpAttemptCount == null ? 'IP 시도 수 없음' : `최근 10분 같은 IP 시도 ${c.recentIpAttemptCount}회`],
    ['예매 간격 분산 감점', points(c.variancePenalty), c.variancePenalty == null ? '이번에 새로 계산하지 않음'
      : c.intervalVariance == null ? '분산 계산 불가' : `간격 분산 ${c.intervalVariance.toFixed(2)}초²`],
    ['룰 감점 합계', points(c.rulePenalty), c.rulePenalty == null ? '—'
      : `상한 적용 전 ${points(c.rulePenaltyRaw)} · 상한 30`],
    ['전체 동적 감점', points(c.dynamicPenalty), c.kind === 'ATTENDANCE'
      ? `이전 평가의 감점을 그대로 사용${c.sourceCalculationId ? ` (산출 #${c.sourceCalculationId})` : ''}` : 'AI 감점 + 룰 감점 · 상한 70'],
    ['최종 Credit', `${c.scoreAfter}`, `${points(c.baseCredit)} − ${points(c.dynamicPenalty)} = ${points(c.rawCredit)} → 0~100 제한 후 반올림`],
  ]

  return (
    <section className="page user-detail">
      <h1 className="section-label big">사용자 조회</h1>
      <div className="summary-card">
        <div className="who"><b>{user.externalUserId}</b><small className="mono">{user.userId}</small>
          <span className={`pill ${user.accountStatus === 'ACTIVE' ? 'ok' : 'off'}`}>{user.accountStatus}</span></div>
        <div className="metric"><span>Credit 등급</span><b>{user.tier ?? '—'}</b></div>
        <div className="metric"><span>크레딧</span><b>{number(user.creditScore)}</b></div>
        <div className="metric"><span>이상 점수</span><b className={user.anomalyScore != null && user.anomalyScore >= 0.7 ? 'danger' : ''}>{score(user.anomalyScore)}</b></div>
      </div>

      <h2 className="section-label">크레딧</h2>
      <div className="block">
        <p className="block-main">현재 점수 <b>{number(user.creditScore)}</b> · 등급 <b>{user.tier ?? '—'}</b>
          <span className="sub">적용된 동적 감점 {detail.dynamicPenalty == null ? '기록 없음' : `${points(detail.dynamicPenalty)}점`}</span></p>

        <div className="feature-head"><b>최신 Credit 산출 근거</b>
          {calculation
            ? <span>{KIND_TEXT[calculation.kind] ?? calculation.kind} · {dateTimeUtc(calculation.createdAt)} · {calculation.reason}</span>
            : <span>기록 없음</span>}</div>
        {calculation ? (
          <table className="feature-table calc-table">
            <tbody>{calculationRows(calculation).map(([label, value, note]) => (
              <tr key={label}><td>{label}</td><td className="right"><b className={value === '—' ? 'na' : ''}>{value}</b></td><td className="calc-note">{note}</td></tr>
            ))}</tbody>
          </table>
        ) : <p className="block-sub">이 사용자의 현재 점수는 산출 근거를 저장하기 전에 계산됐습니다. 다음에 Credit이 다시 계산되면 기본 Credit과 감점 내역이 여기에 표시됩니다.</p>}

        <div className="feature-head"><b>Credit 변경 이력</b>
          {history ? <span>전체 {number(historyTotal)}건{historyTotal > history.length && ` · 최근 ${history.length}건 표시`}</span>
            : historyError ? <span className="danger">조회 실패: {historyError}</span> : <span>불러오는 중…</span>}</div>
        {history && (history.length ? (
          <table className="history-table">
            <thead><tr><th>변경 시각</th><th>점수</th><th>사유</th><th className="right">기본</th><th className="right">AI 감점</th><th className="right">룰 감점</th><th className="right">전체 감점</th></tr></thead>
            <tbody>{history.map(h => (
              <tr key={h.historyId}>
                <td>{dateTimeUtc(h.createdAt)}</td>
                <td>{h.scoreBefore} → <b className="inline">{h.scoreAfter}</b></td>
                <td>{h.reason}</td>
                {h.calculation ? (
                  <>
                    <td className="right">{points(h.calculation.baseCredit)}</td>
                    <td className="right">{points(h.calculation.aiPenalty)}</td>
                    <td className="right">{points(h.calculation.rulePenalty)}</td>
                    <td className="right">{points(h.calculation.dynamicPenalty)}</td>
                  </>
                ) : <td colSpan={4} className="right na">산출 근거 기록 없음</td>}
              </tr>
            ))}</tbody>
          </table>
        ) : <p className="block-sub">Credit 변경 이력이 없습니다.</p>)}
      </div>

      <h2 className="section-label">이상 점수</h2>
      <div className="block">
        <p className="block-main">최근 성공 평가의 이상 점수 <b>{score(user.anomalyScore)}</b>
          <span className="sub">{user.lastAssessedAt ? `평가 ${dateTimeUtc(user.lastAssessedAt)}` : '성공한 평가 없음'}</span></p>
        <p className="block-sub">최근 평가 시도 <span className={`pill ${STATUS_PILL[status] ?? 'off'}`}>{STATUS_TEXT[status] ?? status}</span>
          {latestAttempt
            ? <span className="sub">{dateTimeUtc(latestAttempt.calculatedAt)}{latestAttempt.reason ? ` · ${latestAttempt.reason}` : ''}</span>
            : status === 'OK' && <span className="sub">시도 이력을 남기기 전에 평가됨</span>}</p>
        <p className="block-sub">현재 예상 정책 <span className={`pill ${POLICY_PILL[detail.expectedPolicy] ?? 'off'}`}>{POLICY_TEXT[detail.expectedPolicy] ?? detail.expectedPolicy}</span>
          <span className="sub">현재 Credit과 이상 점수 기준의 예상이며, 실제 내려진 결정이 아닙니다.</span></p>
        <p className="block-sub">최근 정책 결정 {policy
          ? <><b className="inline">{POLICY_TEXT[policy.action] ?? policy.action}</b> · {policy.reason} <span className="sub">({dateTimeUtc(policy.decidedAt)})</span></>
          : <span className="na">없음</span>}</p>

        <div className="feature-head"><b>Feature 6개</b>
          {features ? <span>현재 값: 최근 {features.windowDays}일 기준 · 계산 {dateTimeUtc(features.calculatedAt)}</span>
            : featuresError ? <span className="danger">현재 값 조회 실패: {featuresError}</span> : <span>계산 중…</span>}</div>
        <table className="feature-table">
          <thead><tr><th>Feature</th><th>설명</th><th className="right">평가 당시 (AI에 보낸 값)</th><th className="right">현재 계산값</th></tr></thead>
          <tbody>{FEATURES.map(([key, label, unit]) => (
            <tr key={key}><td><code>{key}</code></td><td>{label}</td>
              <td className="right">{featureValue(assessedFeatures, key, unit)}</td>
              <td className="right">{features ? featureValue(features, key, unit) : <span className="na">—</span>}</td></tr>
          ))}</tbody>
        </table>
        {!assessedFeatures && <p className="block-sub">{assessments.length
          ? '최근 성공 평가는 평가 당시 Feature를 저장하기 전에 수행됐습니다. 다음 평가부터 표시됩니다.'
          : '성공한 AI 평가가 없어 평가 당시 Feature가 없습니다.'}</p>}

        {assessments.length > 0 && (
          <details className="assess"><summary>성공한 평가 이력 {assessments.length}건 보기</summary>
            <table><thead><tr><th>평가 시각</th><th>이상 점수</th><th>연결된 조치</th><th>모델</th></tr></thead>
              <tbody>{assessments.map(a => <tr key={a.assessmentId}><td>{dateTimeUtc(a.assessedAt)}</td><td>{score(a.anomalyScore)}</td><td>{a.policyAction ?? '—'}</td><td className="mono">{a.modelVersion ?? '—'}</td></tr>)}</tbody></table>
          </details>
        )}
        <details className="assess" open={!!attemptHistory?.length}>
          <summary>AI 평가 시도 이력 {attemptHistory ? `${attemptHistory.length}건` : ''}</summary>
          {attemptError ? <p className="block-sub danger">조회 실패: {attemptError}</p>
            : !attemptHistory ? <p className="block-sub">불러오는 중…</p>
            : attemptHistory.length ? (
              <table><thead><tr><th>분석 시각</th><th>상태</th><th>이상 점수</th><th>평가 불가 사유</th><th>모델</th></tr></thead>
                <tbody>{attemptHistory.map(a => (
                  <tr key={a.attemptId}>
                    <td>{dateTimeUtc(a.calculatedAt)}</td>
                    <td><span className={`pill ${STATUS_PILL[a.status] ?? 'off'}`}>{STATUS_TEXT[a.status] ?? a.status}</span></td>
                    <td>{a.anomalyScore == null ? <span className="na">점수 없음</span> : score(a.anomalyScore)}</td>
                    <td>{a.reason ?? '—'}</td>
                    <td className="mono">{a.modelVersion ?? '—'}</td>
                  </tr>
                ))}</tbody></table>
            ) : <p className="block-sub">기록된 평가 시도가 없습니다.{status === 'OK' && ' 이 사용자의 평가는 시도 이력을 남기기 전에 수행됐습니다.'}</p>}
        </details>
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
