import { Fragment, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, ChevronRight } from 'lucide-react'
import {
  Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { api } from '../api/client'
import type {
  AdminUserDetail, Assessment, AssessmentAttempt, AssessmentFeatures, BookingHistory, Concert, CreditCalculation,
  CreditHistoryItem, PageResponse, UserEvent, UserFeatures,
} from '../api/types'
import { useApi } from '../hooks'
import {
  ANIM, Badge, COLOR, CopyButton, Empty, ErrorState, Hint, Kpis, Panel, PolicyBadge, RefreshButton, Skeleton, SkeletonBlock, SkeletonRows,
  StatusBadge, TIER_BOUNDS, Tabs, TierBadge, statusInfo, tierInfo, useMore,
} from '../ui'
import type { Tone } from '../ui'
import { dateTime, dateTimeSec, decimal, number, score, timeMs } from '../utils'
import type { Session } from '../utils'
import type { Attempt } from './attempts'
import { FAILED_STATUSES, eventStage, eventStatus, groupAttempts, STAGE_LABEL, STATUS_LABEL } from './attempts'

interface Props {
  session: Session
  userId: string
  concerts: Concert[]
  onLoaded: (label: string) => void
  onBack: () => void
  onOpenConcert: (concertId: string, name?: string) => void
}

type Tab = 'overview' | 'credit' | 'ai' | 'activity'

/** 모델이 쓰는 행동 특성 6개: 화면용 명칭, 정의, 단위. 값의 범위가 서로 달라 한 축에 정규화하지 않습니다. */
const FEATURES: { key: keyof AssessmentFeatures; name: string; definition: string; unit: string }[] = [
  { key: 'bookingCount', name: '예매 시도 횟수', definition: '최근 30일 동안 예매를 시도한 횟수입니다.', unit: '회' },
  { key: 'avgBookingInterval', name: '평균 시도 간격', definition: '연속한 예매 시도가 시작된 시각 사이 간격의 평균입니다.', unit: '초' },
  { key: 'bookingIntervalVariance', name: '시도 간격의 고르기', definition: '연속한 예매 시도 간격의 분산입니다. 0에 가까울수록 간격이 일정합니다.', unit: '초²' },
  { key: 'cancelRate', name: '취소 비율', definition: '예매를 완료한 건 가운데 취소한 건의 비율입니다.', unit: '%' },
  { key: 'cardReuseCount', name: '결제수단 공유 계정', definition: '같은 결제수단을 사용한 계정 수입니다. 본인을 포함합니다.', unit: '명' },
  { key: 'ipBookingDensity', name: '같은 IP 예매 계정', definition: '같은 IP에서 예매한 계정 수입니다. 본인을 포함합니다.', unit: '명' },
]
const KIND_TEXT: Record<string, string> = { EVALUATION: 'AI 평가 반영', ATTENDANCE: '관람 이력 반영' }
const CONTEXT_TEXT: Record<string, string> = { QUEUE_JOIN: '대기열 진입', BOOKING_ATTEMPT: '예매 시도' }
const BOOKING_STATUS: Record<string, { label: string; tone: Tone }> = {
  BOOKED: { label: '예매됨', tone: 'neutral' }, COMPLETED: { label: '관람 완료', tone: 'ok' }, NO_SHOW: { label: '노쇼', tone: 'danger' },
  AUTO_CANCELLED: { label: '자동 취소', tone: 'warn' }, CANCELLED: { label: '취소', tone: 'muted' },
}
const EVENT_TEXT: Record<string, string> = { BOOKING: '예매 완료', CANCEL: '예매 취소', CHECK_IN: '입장', BOOKING_ATTEMPT: '예매 진행' }
const EVENT_PAGES = 3
const EVENT_SIZE = 100

const featureText = (source: AssessmentFeatures | UserFeatures | null | undefined, key: keyof AssessmentFeatures, unit: string) => {
  if (!source) return null
  const v = source[key]
  if (typeof v !== 'number') return null
  return key === 'cancelRate' ? `${(v * 100).toFixed(1)}%` : `${decimal(v)}${unit}`
}

export default function UserDetail({ session, userId, concerts, onLoaded, onBack, onOpenConcert }: Props) {
  const key = session.adminKey
  const [tab, setTab] = useState<Tab>('overview')
  const [visited, setVisited] = useState<Set<Tab>>(new Set(['overview']))
  const [refreshKey, setRefreshKey] = useState(0)
  const open = (next: Tab) => { setTab(next); setVisited(v => new Set(v).add(next)) }
  const deps = [key, userId, refreshKey]

  const detail = useApi<AdminUserDetail>(() => api.user(key, userId), deps)
  // 탭별 데이터는 그 탭을 처음 열 때 불러옵니다.
  const history = useApi<PageResponse<CreditHistoryItem>>(() => api.userCreditHistory(key, userId, { size: 50 }), deps, { skip: !visited.has('credit') })
  const features = useApi<UserFeatures>(() => api.userFeatures(key, userId), deps, { skip: !visited.has('ai') })
  const assessments = useApi<PageResponse<Assessment>>(() => api.userAssessments(key, userId, { size: 50 }), deps, { skip: !visited.has('ai') })
  const attemptHistory = useApi<PageResponse<AssessmentAttempt>>(() => api.userAssessmentAttempts(key, userId, { size: 20 }), deps, { skip: !visited.has('ai') })
  const attemptEvents = useApi<{ events: UserEvent[]; truncated: boolean }>(async () => {
    const all: UserEvent[] = []
    for (let p = 0; p < EVENT_PAGES; p++) {
      const page = await api.userEvents(key, userId, { eventType: 'BOOKING_ATTEMPT', page: p, size: EVENT_SIZE })
      all.push(...page.content)
      if (!page.hasNext) return { events: all, truncated: false }
    }
    return { events: all, truncated: true }
  }, deps, { skip: !visited.has('activity') })
  const bookings = useApi<PageResponse<BookingHistory>>(() => api.userBookings(key, userId, { size: 50 }), deps, { skip: !visited.has('activity') })
  const recent = useApi<PageResponse<UserEvent>>(() => api.userEvents(key, userId, { size: 50 }), deps, { skip: !visited.has('activity') })

  const d = detail.data
  const user = d?.user
  useEffect(() => { if (user) onLoaded(user.externalUserId) }, [user, onLoaded])

  if (detail.error) {
    return (
      <>
        <div className="entity"><h1>사용자 상세</h1><button className="btn" onClick={onBack}><ArrowLeft size={14} />사용자 목록</button></div>
        <div className="panel"><ErrorState message={detail.error} onRetry={() => setRefreshKey(k => k + 1)} /></div>
      </>
    )
  }

  const policy = d?.latestPolicy
  const calc = d?.latestCalculation
  const latestAttempt = d?.latestAttempt
  const concertName = (id: string | null | undefined) => (id ? concerts.find(c => c.concertId === id)?.name ?? id : '—')
  const anomalous = user?.anomalyScore != null && user.anomalyScore >= 0.5

  return (
    <div>
      <div className="entity">
        <div>
          <h1>{user ? user.externalUserId : <Skeleton w={200} h={28} />}
            {user && <Badge tone={user.accountStatus === 'ACTIVE' ? 'ok' : 'muted'}>{user.accountStatus === 'ACTIVE' ? '활성' : user.accountStatus === 'SUSPENDED' ? '정지' : user.accountStatus}</Badge>}</h1>
          {user && (
            <div className="entity-meta">
              <span>내부 ID</span><span className="mono">{user.userId}</span><CopyButton value={user.userId} />
              <span>· 등록 {dateTime(user.createdAt)}</span>
            </div>
          )}
        </div>
        <div className="page-actions">
          <RefreshButton onClick={() => setRefreshKey(k => k + 1)} loading={detail.loading} loadedAt={detail.loadedAt} />
          <button className="btn" onClick={onBack}><ArrowLeft size={14} />사용자 목록</button>
        </div>
      </div>

      <Kpis compact loading={!d} items={[
        { label: 'Credit', value: number(user?.creditScore), unit: '점', hint: '0~100점이며 높을수록 신뢰할 수 있는 사용자입니다.' },
        { label: '위험 등급', value: <TierBadge tier={user?.tier} />, text: true, hint: 'Credit 40점 이상 정상, 20~39점 의심, 19점 이하 위험입니다.' },
        user?.anomalyScore == null
          ? { label: 'AI 이상 점수', value: '미평가', text: true, na: true, hint: 'AI 평가 결과가 아직 없습니다. 0점으로 간주하지 않습니다.' }
          : { label: 'AI 이상 점수', value: score(user.anomalyScore), sub: anomalous ? '이상 탐지' : '이상 아님',
            hint: '가장 최근에 성공한 AI 평가의 점수입니다. 0~1 사이 값이며 0.5 이상이면 이상 행동으로 판정합니다. 확률이 아닙니다.' },
        { label: '최근 AI 평가', value: <StatusBadge status={d?.assessmentStatus} />, text: true,
          sub: latestAttempt ? dateTime(latestAttempt.calculatedAt) : undefined, hint: '가장 최근 평가 시도의 결과입니다. 데이터가 부족하면 점수가 나오지 않을 수 있습니다.' },
        { label: '예상 정책', value: <PolicyBadge action={d?.expectedPolicy} />, text: true, hint: '지금 예매를 요청하면 받게 될 조치입니다. 실제로 적용된 정책과는 다릅니다.' },
        policy
          ? { label: '최근 정책 결정', value: <PolicyBadge action={policy.action} />, text: true, sub: dateTime(policy.decidedAt), hint: '티켓팅 서비스의 요청에 실제로 내려진 가장 최근 결정입니다.' }
          : { label: '최근 정책 결정', value: '없음', text: true, na: true, hint: '이 사용자에게 내려진 정책 결정이 아직 없습니다.' },
      ]} />

      <div style={{ marginTop: 20 }}>
        <Tabs<Tab> value={tab} onChange={open} tabs={[['overview', '개요'], ['credit', 'Credit 분석'], ['ai', 'AI 분석'], ['activity', '활동 이력']]} />
      </div>

      {tab === 'overview' && (!d ? <SkeletonBlock h={240} /> : (
        <div className="grid cols-3 tab-panel" key="overview">
          <Panel title="정책">
            <dl className="kv">
              <dt>예상 정책</dt><dd><PolicyBadge action={d.expectedPolicy} /></dd>
              <dt>최근 결정</dt><dd>{policy ? <PolicyBadge action={policy.action} /> : <span className="na">없음</span>}</dd>
              {policy && <>
                <dt>결정 시각</dt><dd className="tnum">{dateTime(policy.decidedAt)}</dd>
                <dt>요청 시점</dt><dd>{CONTEXT_TEXT[policy.context] ?? policy.context}</dd>
                <dt>사유</dt><dd>{policy.reason}</dd>
                <dt>당시 값</dt><dd className="num">Credit {policy.creditScore} · 이상 점수 {score(policy.anomalyScore)}</dd>
                {policy.concertId && <><dt>공연</dt><dd>
                  <button className="btn link" onClick={() => onOpenConcert(policy.concertId!, concertName(policy.concertId))}>{concertName(policy.concertId)}</button></dd></>}
              </>}
            </dl>
          </Panel>
          <Panel title="최근 Credit 산출" actions={<button className="btn link" onClick={() => open('credit')}>Credit 분석</button>}>
            {calc ? (
              <dl className="kv">
                <dt>점수 변화</dt><dd className="num">{calc.scoreBefore} → <b>{calc.scoreAfter}</b></dd>
                <dt>산출 시각</dt><dd className="tnum">{dateTime(calc.createdAt)}</dd>
                <dt>산출 계기</dt><dd>{KIND_TEXT[calc.kind] ?? calc.kind}</dd>
                <dt>기본 Credit</dt><dd className="num">{decimal(calc.baseCredit)}</dd>
                <dt>감점 합계</dt><dd className="num">{decimal(calc.dynamicPenalty)}</dd>
              </dl>
            ) : <Empty title="산출 기록 없음">다음에 Credit이 산출되면 근거가 표시됩니다.</Empty>}
          </Panel>
          <Panel title="AI 평가" actions={<button className="btn link" onClick={() => open('ai')}>AI 분석</button>}>
            <dl className="kv">
              <dt>이상 점수</dt><dd className="num">{user!.anomalyScore == null ? <span className="na">미평가</span> : <b>{score(user!.anomalyScore)}</b>}</dd>
              <dt>평가 시각</dt><dd className="tnum">{dateTime(user!.lastAssessedAt)}</dd>
              <dt>최근 시도</dt><dd><StatusBadge status={d.assessmentStatus} /></dd>
              {latestAttempt?.reason && <><dt>상태 설명</dt><dd>{latestAttempt.reason}</dd></>}
            </dl>
            {d.assessmentStatus !== 'OK' && user!.anomalyScore != null &&
              <p className="note" style={{ marginTop: 12 }}>최근 시도에서는 점수가 나오지 않아, 그 이전에 성공한 평가의 점수를 표시합니다.</p>}
          </Panel>
        </div>
      ))}

      {tab === 'credit' && (
        <div className="stack tab-panel" key="credit">
          <Panel>
            {!d ? <SkeletonBlock h={90} /> : (
              <div className="hero">
                <div>
                  <div className="hero-label">현재 Credit</div>
                  <div className="hero-num"><b>{number(user?.creditScore)}</b><span>/ 100점</span></div>
                  <div className="hero-state"><TierBadge tier={user?.tier} />{calc && <span>· {dateTime(calc.createdAt)} 산출</span>}</div>
                </div>
                <div>
                  <div className="gauge for-credit" role="img" aria-label={`Credit ${user?.creditScore ?? '없음'}점`}>
                    {user?.creditScore != null && <span className="gauge-mark" style={{ left: `${Math.max(0, Math.min(100, user.creditScore))}%` }} />}
                  </div>
                  <div className="gauge-scale">
                    <span style={{ left: 0 }}>0</span>
                    <span style={{ left: '10%' }}>위험</span>
                    <span className="key" style={{ left: `${TIER_BOUNDS.suspicious}%` }}>{TIER_BOUNDS.suspicious}</span>
                    <span style={{ left: '30%' }}>의심</span>
                    <span className="key" style={{ left: `${TIER_BOUNDS.normal}%` }}>{TIER_BOUNDS.normal}</span>
                    <span style={{ left: '70%' }}>정상</span>
                    <span style={{ left: '100%' }}>100</span>
                  </div>
                </div>
              </div>
            )}
          </Panel>

          <Panel title="Credit 산출 근거" flush hint="가장 최근 산출 당시 저장된 값입니다. 기본 Credit은 관람 이력으로 정해지고, AI 평가와 규칙에 따른 감점을 뺀 값이 최종 Credit이 됩니다."
            sub={calc ? `${dateTime(calc.createdAt)} · ${KIND_TEXT[calc.kind] ?? calc.kind}` : undefined}>
            {!d ? <div style={{ padding: 20 }}><SkeletonBlock h={260} /></div>
              : calc ? <CreditBreakdown c={calc} />
              : <Empty title="산출 기록 없음">현재 점수는 산출 근거를 기록하기 전에 정해졌습니다. 다음에 Credit이 산출되면 기본 Credit과 감점 내역이 표시됩니다.</Empty>}
          </Panel>

          <Panel title="Credit 변화" flush sub={history.data ? `변경 ${number(history.data.totalElements)}건${history.data.totalElements > history.data.content.length ? ` 중 최근 ${history.data.content.length}건` : ''}` : undefined}>
            {history.error ? <ErrorState message={history.error} onRetry={history.reload} />
              : !history.data ? <div style={{ padding: 20 }}><SkeletonBlock h={200} /></div>
              : !history.data.content.length ? <Empty title="Credit 변경 이력이 없습니다" />
              : <CreditHistory items={history.data.content} />}
          </Panel>
        </div>
      )}

      {tab === 'ai' && (
        <div className="stack tab-panel" key="ai">
          <Panel>
            {!d ? <SkeletonBlock h={90} /> : (
              <div className="hero">
                <div>
                  <div className="hero-label">AI 이상 점수<Hint text="행동이 평소 예매 패턴에서 얼마나 벗어났는지를 0~1로 나타낸 값입니다. 부정 예매일 확률이 아닙니다." /></div>
                  <div className="hero-num">{user!.anomalyScore == null ? <b className="na">미평가</b> : <b>{score(user!.anomalyScore)}</b>}</div>
                  <div className="hero-state">
                    {user!.anomalyScore == null ? <span>AI 평가 결과가 아직 없습니다</span>
                      : <><Badge tone={anomalous ? 'danger' : 'neutral'}>{anomalous ? '이상 탐지' : '이상 아님'}</Badge><span>· {dateTime(user!.lastAssessedAt)} 평가</span></>}
                  </div>
                </div>
                <div>
                  <div className="gauge for-score" role="img" aria-label={user!.anomalyScore == null ? '미평가' : `이상 점수 ${score(user!.anomalyScore)}, 탐지 기준 0.5`}>
                    {user!.anomalyScore != null && <span className="gauge-mark" style={{ left: `${Math.max(0, Math.min(1, user!.anomalyScore)) * 100}%` }} />}
                  </div>
                  <div className="gauge-scale">
                    <span style={{ left: 0 }}>0</span>
                    <span style={{ left: '25%' }}>이상 아님</span>
                    <span className="key" style={{ left: '50%' }}>탐지 기준 0.5</span>
                    <span style={{ left: '75%' }}>이상 탐지</span>
                    <span style={{ left: '100%' }}>1</span>
                  </div>
                </div>
              </div>
            )}
            {d && d.assessmentStatus !== 'OK' && d.assessmentStatus !== 'NOT_EVALUATED' && (
              <p className="note" style={{ marginTop: 16 }}>가장 최근 평가 시도는 <b>{statusInfo(d.assessmentStatus).label}</b>(으)로 끝나 점수가 나오지 않았습니다.
                {user!.anomalyScore != null && ' 위 점수는 그 이전에 성공한 평가의 값입니다.'}</p>
            )}
          </Panel>

          {assessments.error
            ? <Panel title="행동 특성"><ErrorState message={assessments.error} onRetry={assessments.reload} /></Panel>
            : <FeaturePanel assessed={assessments.data?.content[0] ?? null} assessmentsLoaded={!!assessments.data} features={features} />}

          <Panel title="이상 점수 변화" flush hint="성공한 AI 평가의 점수를 시간순으로 표시합니다. 점수가 나오지 않은 평가 시도는 포함하지 않습니다."
            sub={assessments.data ? `성공한 평가 ${number(assessments.data.totalElements)}건${assessments.data.totalElements > assessments.data.content.length ? ` 중 최근 ${assessments.data.content.length}건` : ''}` : undefined}>
            {assessments.error ? <ErrorState message={assessments.error} onRetry={assessments.reload} />
              : !assessments.data ? <div style={{ padding: 20 }}><SkeletonBlock h={200} /></div>
              : !assessments.data.content.length ? <Empty title="성공한 AI 평가가 없습니다">점수가 산출되면 변화 그래프가 표시됩니다.</Empty>
              : <ScoreTrend items={assessments.data.content} />}
            {assessments.data && assessments.data.content.length > 0 && (
              <details className="more">
                <summary>성공한 평가 이력 <small>{number(assessments.data.content.length)}건</small></summary>
                <div className="tbl-wrap"><table className="tbl dense">
                  <thead><tr><th>평가 시각</th><th className="num">이상 점수</th><th>판정</th><th>모델 버전</th></tr></thead>
                  <tbody>{assessments.data.content.map(a => (
                    <tr key={a.assessmentId}>
                      <td className="tnum muted">{dateTimeSec(a.assessedAt)}</td>
                      <td className="num"><b>{score(a.anomalyScore)}</b></td>
                      <td>{a.anomalyScore == null ? '—' : a.anomalyScore >= 0.5 ? <Badge tone="danger">이상 탐지</Badge> : <Badge tone="neutral">이상 아님</Badge>}</td>
                      <td className="mono muted">{a.modelVersion ?? '—'}</td>
                    </tr>
                  ))}</tbody>
                </table></div>
              </details>
            )}
            <details className="more">
              <summary>전체 평가 시도 이력 {attemptHistory.data && <small>{number(attemptHistory.data.content.length)}건</small>}</summary>
              {attemptHistory.error ? <ErrorState message={attemptHistory.error} onRetry={attemptHistory.reload} />
                : !attemptHistory.data ? <SkeletonRows rows={3} cols={4} />
                : !attemptHistory.data.content.length ? (
                  <Empty title="기록된 평가 시도가 없습니다">{d?.assessmentStatus === 'OK' ? '이 사용자의 평가는 시도 이력을 기록하기 전에 수행됐습니다.' : undefined}</Empty>
                ) : (
                  <div className="tbl-wrap"><table className="tbl dense">
                    <thead><tr><th>평가 시각</th><th>결과</th><th className="num">이상 점수</th><th>설명</th><th>모델 버전</th></tr></thead>
                    <tbody>{attemptHistory.data.content.map(a => (
                      <tr key={a.attemptId}>
                        <td className="tnum muted">{dateTimeSec(a.calculatedAt)}</td>
                        <td><StatusBadge status={a.status} /></td>
                        <td className="num">{a.anomalyScore == null ? <span className="na">—</span> : score(a.anomalyScore)}</td>
                        <td className="muted">{a.reason ?? '—'}</td>
                        <td className="mono muted">{a.modelVersion ?? '—'}</td>
                      </tr>
                    ))}</tbody>
                  </table></div>
                )}
            </details>
          </Panel>
        </div>
      )}

      {tab === 'activity' && (
        <div className="stack tab-panel" key="activity">
          <AttemptPanel state={attemptEvents} concertName={concertName} onOpenConcert={onOpenConcert} />
          <div className="grid cols-2">
            <BookingPanel state={bookings} />
            <EventPanel state={recent} />
          </div>
        </div>
      )}
    </div>
  )
}

/* ── Credit 산출 근거 ── */
interface Step { name: string; range: [number, number]; delta: number | null; value: number; kind: 'total' | 'minus' | 'plus' | 'none'; note: string; minor?: boolean }

/**
 * 백엔드가 저장한 산출 기록을 단계로 펼칩니다. 계산식을 다시 실행하지 않고 기록된 값의 차이만 사용하며,
 * 단계를 모두 적용한 중간값이 기록된 값과 다르면 consistent가 false가 됩니다.
 */
function buildSteps(c: CreditCalculation): { steps: Step[]; consistent: boolean } {
  const steps: Step[] = []
  let run = c.baseCredit
  steps.push({ name: '기본 Credit', range: [0, run], delta: null, value: run, kind: 'total',
    note: `관람 완료 ${c.completedCount}회 · 노쇼 ${c.noShowCount}회 · 자동 취소 ${c.autoCancelledCount}회` })
  const move = (name: string, delta: number, note: string, minor = false) => {
    const next = run + delta
    steps.push({ name, range: [Math.min(run, next), Math.max(run, next)], delta, value: next, kind: delta < 0 ? 'minus' : delta > 0 ? 'plus' : 'none', note, minor })
    run = next
  }
  if (c.aiPenalty != null || c.rulePenalty != null) {
    if (c.aiPenalty != null) move('AI 이상 행동', -c.aiPenalty, c.anomalyScore == null ? '이상 점수 없음' : `이상 점수 ${score(c.anomalyScore)}`)
    if (c.cardPenalty != null) move('결제수단 공유', -c.cardPenalty, c.cardSharedAccounts == null ? '기록 없음' : `같은 결제수단을 쓴 계정 ${c.cardSharedAccounts}개`)
    if (c.ipPenalty != null) move('같은 IP 집중', -c.ipPenalty, c.recentIpAttemptCount == null ? '기록 없음' : `최근 같은 IP에서 시도 ${c.recentIpAttemptCount}회`)
    if (c.variancePenalty != null) move('일정한 예매 간격', -c.variancePenalty, c.intervalVariance == null ? '간격 계산 불가' : `시도 간격 분산 ${decimal(c.intervalVariance)}초²`)
    if (c.rulePenaltyRaw != null && c.rulePenalty != null && c.rulePenaltyRaw - c.rulePenalty > 0.005) {
      move('규칙 감점 상한', c.rulePenaltyRaw - c.rulePenalty, `규칙 감점 합계 ${decimal(c.rulePenaltyRaw)}점 중 상한 ${decimal(c.rulePenalty)}점까지만 적용`)
    }
    const sum = (c.aiPenalty ?? 0) + (c.rulePenalty ?? 0)
    if (sum - c.dynamicPenalty > 0.005) move('전체 감점 상한', sum - c.dynamicPenalty, `감점 합계 ${decimal(sum)}점 중 상한 ${decimal(c.dynamicPenalty)}점까지만 적용`)
  } else {
    move('이전 평가의 감점', -c.dynamicPenalty, '관람 이력만 반영한 산출이라 이전 평가의 감점을 그대로 적용')
  }
  const consistent = Math.abs(run - c.rawCredit) < 0.01
  run = c.rawCredit
  if (Math.abs(c.scoreAfter - c.rawCredit) >= 0.005) move('범위 제한 · 반올림', c.scoreAfter - c.rawCredit, `${decimal(c.rawCredit)}점을 0~100 범위의 정수로 조정`, true)
  steps.push({ name: '최종 Credit', range: [0, c.scoreAfter], delta: null, value: c.scoreAfter, kind: 'total', note: `${tierInfo(c.tier).label} 등급` })
  return { steps, consistent }
}
const signed = (s: Step) => s.delta == null ? decimal(s.value) : s.delta === 0 ? '0' : `${s.delta > 0 ? '+' : '−'}${decimal(Math.abs(s.delta))}`

function CreditBreakdown({ c }: { c: CreditCalculation }) {
  const { steps, consistent } = buildSteps(c)
  // 그래프에는 실제로 점수를 움직인 단계만 그립니다. 0점 항목과 반올림은 상세 산출 내역에 있습니다.
  const major = steps.filter(s => s.kind === 'total' || (s.delta !== 0 && !s.minor))
  const causes = steps.filter(s => s.kind === 'minus').sort((a, b) => (a.delta ?? 0) - (b.delta ?? 0))
  const maxCause = Math.max(1, ...causes.map(s => Math.abs(s.delta ?? 0)))
  const low = Math.min(0, ...major.map(s => s.range[0]))
  const finalTone = tierInfo(c.tier).tone
  const fill = (s: Step, last: boolean) => s.kind === 'minus' ? COLOR.danger : s.kind === 'plus' ? COLOR.inkSoft
    : last ? (finalTone === 'danger' ? COLOR.danger : finalTone === 'warn' ? COLOR.warn : COLOR.ink) : COLOR.inkFaint
  return (
    <>
      {!consistent && <div style={{ padding: '0 20px 12px' }}><ErrorState inline message="산출 기록의 세부 감점 합계가 기록된 중간값과 일치하지 않습니다. 상세 산출 내역의 기록값을 확인해 주세요." /></div>}
      <div className="grid cols-1-2" style={{ padding: '4px 20px 20px', alignItems: 'center' }}>
        <div>
          <div className="sub-title">주요 감점 원인</div>
          {causes.length ? (
            <div className="causes">{causes.map(s => (
              <div key={s.name}>
                <div className="cause-top"><span>{s.name}<small>{s.note}</small></span><b>{signed(s)}점</b></div>
                <div className="hbar-track"><i style={{ width: `${(Math.abs(s.delta ?? 0) / maxCause) * 100}%`, background: COLOR.danger }} /></div>
              </div>
            ))}</div>
          ) : <p className="note">이번 산출에서 적용된 감점이 없습니다.</p>}
        </div>
        <ResponsiveContainer width="100%" height={260}>
          <BarChart data={major} margin={{ top: 8, right: 8, bottom: 0, left: -16 }} barCategoryGap="26%">
            <CartesianGrid stroke={COLOR.grid} vertical={false} />
            <XAxis dataKey="name" tickLine={false} axisLine={{ stroke: COLOR.grid }} interval={0} tick={{ fontSize: 11.5 }} />
            <YAxis domain={[Math.floor(low / 10) * 10, 100]} tickLine={false} axisLine={false} width={48} ticks={low < 0 ? undefined : [0, 20, 40, 60, 80, 100]} />
            {low < 0 && <ReferenceLine y={0} stroke={COLOR.muted} />}
            <Tooltip cursor={{ fill: COLOR.hover }} content={({ active, payload }) => {
              const s = active ? payload?.[0]?.payload as Step | undefined : undefined
              return s ? (
                <div className="chart-tip"><b>{s.name}</b>
                  {s.delta != null && <div>변화<em>{signed(s)}점</em></div>}
                  <div>{s.delta == null ? '점수' : '적용 후'}<em>{decimal(s.value)}점</em></div>
                  <div style={{ maxWidth: 240 }}>{s.note}</div>
                </div>
              ) : null
            }} />
            <Bar dataKey="range" radius={3} minPointSize={2} {...ANIM}>
              {major.map((s, i) => <Cell key={s.name} fill={fill(s, i === major.length - 1)} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <details className="more">
        <summary>상세 산출 내역 <small>점수 변화 {c.scoreBefore} → {c.scoreAfter}</small></summary>
        <div className="tbl-wrap"><table className="tbl dense">
          <thead><tr><th>단계</th><th className="num">변화</th><th className="num">적용 후</th><th>근거</th></tr></thead>
          <tbody>{steps.map(s => (
            <tr key={s.name}>
              <td>{s.kind === 'total' ? <b>{s.name}</b> : s.name}</td>
              <td className="num">{s.delta == null ? '' : s.delta === 0 ? <span className="na">0</span> : signed(s)}</td>
              <td className="num">{s.kind === 'total' ? <b>{decimal(s.value)}</b> : decimal(s.value)}</td>
              <td className="muted">{s.note}</td>
            </tr>
          ))}</tbody>
        </table></div>
        <p className="note" style={{ padding: '10px 20px 14px' }}>규칙에 따른 감점은 최대 30점, AI 감점과 합친 전체 감점은 최대 70점까지 적용됩니다.</p>
      </details>
    </>
  )
}

/* ── Credit 변화: 계단형 그래프 + 표 ── */
function CreditHistory({ items }: { items: CreditHistoryItem[] }) {
  const { shown, more } = useMore(items, 5)
  const asc = [...items].sort((a, b) => timeMs(a.createdAt) - timeMs(b.createdAt) || a.historyId - b.historyId)
  const first = timeMs(asc[0].createdAt)
  const last = timeMs(asc[asc.length - 1].createdAt)
  const lead = Math.max(1000, (last - first) * 0.06)
  // 첫 변경 직전 점수에서 시작해, 각 변경 시각에 점수가 바뀌는 계단형으로 그립니다.
  const points = [{ t: first - lead, score: asc[0].scoreBefore, label: '첫 변경 전 점수' },
    ...asc.map(h => ({ t: timeMs(h.createdAt), score: h.scoreAfter, label: h.reason }))]
  const span = last - first
  const tick = (t: number) => {
    const s = dateTimeSec(new Date(t).toISOString())
    return span > 2 * 86400000 ? s.slice(5, 10) : span < 3600000 ? s.slice(11) : s.slice(5, 16)
  }
  return (
    <>
      <div style={{ padding: '4px 20px 12px' }}>
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={points} margin={{ top: 10, right: 56, bottom: 0, left: -16 }}>
            <CartesianGrid stroke={COLOR.grid} vertical={false} />
            <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} ticks={[...new Set(points.map(x => x.t))]} tickFormatter={tick}
              tickLine={false} axisLine={{ stroke: COLOR.grid }} minTickGap={56} />
            <YAxis domain={[0, 100]} ticks={[0, 20, 40, 60, 80, 100]} tickLine={false} axisLine={false} width={48} />
            <ReferenceLine y={TIER_BOUNDS.normal} stroke={COLOR.warn} strokeDasharray="4 4" label={{ value: `의심 < ${TIER_BOUNDS.normal}`, position: 'right', fontSize: 11, fill: COLOR.warnText }} />
            <ReferenceLine y={TIER_BOUNDS.suspicious} stroke={COLOR.danger} strokeDasharray="4 4" label={{ value: `위험 < ${TIER_BOUNDS.suspicious}`, position: 'right', fontSize: 11, fill: COLOR.dangerText }} />
            <Tooltip content={({ active, payload }) => {
              const p = active ? payload?.[0]?.payload as typeof points[number] | undefined : undefined
              return p ? <div className="chart-tip"><b>{dateTimeSec(new Date(p.t).toISOString())}</b><div>Credit<em>{p.score}점</em></div><div style={{ maxWidth: 240 }}>{p.label}</div></div> : null
            }} />
            <Line type="stepAfter" dataKey="score" stroke={COLOR.ink} strokeWidth={2} dot={{ r: 3, fill: COLOR.ink, strokeWidth: 0 }} {...ANIM} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className="tbl-wrap" style={{ borderTop: '1px solid var(--border)' }}><table className="tbl dense">
        <thead><tr><th>변경 시각</th><th className="num">점수 변화</th><th>사유</th>
          <th className="num">기본 Credit</th><th className="num">AI 감점</th><th className="num">규칙 감점</th><th className="num">감점 합계</th></tr></thead>
        <tbody>{shown.map(h => (
          <tr key={h.historyId}>
            <td className="tnum muted">{dateTimeSec(h.createdAt)}</td>
            <td className="num"><span className="muted">{h.scoreBefore} →</span> <b>{h.scoreAfter}</b></td>
            <td>{h.reason}</td>
            {h.calculation ? (
              <>
                <td className="num">{decimal(h.calculation.baseCredit)}</td>
                <td className="num">{decimal(h.calculation.aiPenalty)}</td>
                <td className="num">{decimal(h.calculation.rulePenalty)}</td>
                <td className="num">{decimal(h.calculation.dynamicPenalty)}</td>
              </>
            ) : <td colSpan={4} className="num na">산출 근거 기록 없음</td>}
          </tr>
        ))}</tbody>
      </table></div>
      {more}
    </>
  )
}

/* ── AI 분석: 행동 특성 카드 + 입력값 상세 ── */
function FeaturePanel({ assessed, assessmentsLoaded, features }: {
  assessed: Assessment | null; assessmentsLoaded: boolean; features: ReturnType<typeof useApi<UserFeatures>>
}) {
  const atAssessment = assessed?.features ?? null
  const loading = !assessmentsLoaded || (!features.data && !features.error)
  return (
    <Panel title="행동 특성" flush hint="AI 모델이 평가에 사용하는 여섯 가지 값입니다. 모델은 특성별 기여도를 제공하지 않으므로, 어느 값이 점수에 얼마나 영향을 줬는지는 표시하지 않습니다."
      sub={loading ? undefined : atAssessment ? `큰 숫자는 ${dateTime(assessed!.assessedAt)} 평가에 실제로 입력된 값입니다` : '평가 당시 입력값이 없어 현재 계산값을 표시합니다'}>
      {features.error && <div style={{ padding: '0 20px 12px' }}><ErrorState inline message={`현재 계산값을 불러오지 못했습니다. ${features.error}`} onRetry={features.reload} /></div>}
      <div className="features">
        {FEATURES.map(f => {
          const then = featureText(atAssessment, f.key, f.unit)
          const now = featureText(features.data, f.key, f.unit)
          const main = atAssessment ? then : now
          return (
            <div className="feature" key={f.key} data-feature={f.key}>
              <div className="feature-name">{f.name}<Hint text={f.definition} /></div>
              {loading ? <Skeleton w={70} h={26} style={{ marginTop: 8 }} />
                : <div className={`feature-value${main == null ? ' na' : ''}`}>{main ?? '계산 불가'}</div>}
              {!loading && atAssessment && <div className="feature-sub">현재 {features.error ? '—' : now ?? '계산 불가'}</div>}
            </div>
          )
        })}
      </div>
      <details className="more">
        <summary>입력값 상세 <small>평가 당시 값과 현재 계산값 비교</small></summary>
        <div className="tbl-wrap"><table className="tbl dense">
          <thead><tr><th>행동 특성</th><th>정의</th><th className="num">평가 당시 입력값</th><th className="num">현재 계산값</th></tr></thead>
          <tbody>{FEATURES.map(f => (
            <tr key={f.key}>
              <td><b>{f.name}</b></td>
              <td className="muted">{f.definition}</td>
              <td className="num">{!assessmentsLoaded ? <Skeleton w={50} /> : !atAssessment ? <span className="na">기록 없음</span> : featureText(atAssessment, f.key, f.unit) ?? <span className="na">계산 불가</span>}</td>
              <td className="num">{features.error ? <span className="na">—</span> : !features.data ? <Skeleton w={50} /> : featureText(features.data, f.key, f.unit) ?? <span className="na">계산 불가</span>}</td>
            </tr>
          ))}</tbody>
        </table></div>
        <p className="note" style={{ padding: '10px 20px 14px' }}>
          {assessed ? (atAssessment ? '' : '최근 성공한 평가는 입력값을 기록하기 전에 수행되어 평가 당시 값이 없습니다. ') : assessmentsLoaded ? '성공한 AI 평가가 없어 평가 당시 값이 없습니다. ' : ''}
          현재 계산값은 {features.data ? `${dateTime(features.data.calculatedAt)}에 최근 ${features.data.windowDays}일 행동으로 계산한 값` : '조회 시점에 계산한 값'}이라 평가 당시 값과 다를 수 있습니다.
        </p>
      </details>
    </Panel>
  )
}

function ScoreTrend({ items }: { items: Assessment[] }) {
  const points = useMemo(() => items.filter(a => a.anomalyScore != null)
    .map(a => ({ t: timeMs(a.assessedAt), score: a.anomalyScore as number })).sort((a, b) => a.t - b.t), [items])
  const span = points.length ? points[points.length - 1].t - points[0].t : 0
  const tick = (t: number) => {
    const s = dateTimeSec(new Date(t).toISOString())
    return span > 2 * 86400000 ? s.slice(5, 10) : span < 3600000 ? s.slice(11) : s.slice(5, 16)
  }
  return (
    <div style={{ padding: '4px 20px 12px' }}>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={points} margin={{ top: 10, right: 84, bottom: 0, left: -16 }}>
          <CartesianGrid stroke={COLOR.grid} vertical={false} />
          <XAxis dataKey="t" type="number" scale="time" domain={span ? ['dataMin', 'dataMax'] : [points[0].t - 60000, points[0].t + 60000]}
            ticks={[...new Set(points.map(p => p.t))]} tickFormatter={tick} tickLine={false} axisLine={{ stroke: COLOR.grid }} minTickGap={56} />
          <YAxis domain={[0, 1]} ticks={[0, 0.25, 0.5, 0.75, 1]} tickLine={false} axisLine={false} width={48} />
          <ReferenceLine y={0.5} stroke={COLOR.danger} strokeDasharray="4 4" label={{ value: '탐지 기준 0.5', position: 'right', fontSize: 11, fill: COLOR.dangerText }} />
          <Tooltip content={({ active, payload }) => {
            const p = active ? payload?.[0]?.payload as typeof points[number] | undefined : undefined
            return p ? <div className="chart-tip"><b>{dateTimeSec(new Date(p.t).toISOString())}</b><div>이상 점수<em>{score(p.score)}</em></div><div>{p.score >= 0.5 ? '이상 탐지' : '이상 아님'}</div></div> : null
          }} />
          <Line dataKey="score" stroke={COLOR.ink} strokeWidth={2} {...ANIM}
            dot={({ cx, cy, payload, index }) => <circle key={index} cx={cx} cy={cy} r={3.5} fill={payload.score >= 0.5 ? COLOR.danger : COLOR.ink} />} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

/* ── 활동 이력 ── */
function AttemptPanel({ state, concertName, onOpenConcert }: {
  state: ReturnType<typeof useApi<{ events: UserEvent[]; truncated: boolean }>>
  concertName: (id: string | null | undefined) => string
  onOpenConcert: (concertId: string, name?: string) => void
}) {
  const attempts = useMemo(() => (state.data ? groupAttempts(state.data.events) : []), [state.data])
  const { shown, more } = useMore(attempts, 5)
  const [openId, setOpenId] = useState('')
  const resultBadge = (a: Attempt) => <Badge tone={a.result === 'SUCCESS' ? 'ok' : a.result === 'FAILED' ? 'danger' : 'muted'}>
    {a.result === 'SUCCESS' ? '성공' : a.result === 'FAILED' ? '실패' : '미종료'}</Badge>
  return (
    <Panel title="예매 시도" flush hint="예매 한 번의 진행 과정을 하나로 묶어 보여줍니다. 행을 펼치면 단계별 진행 시각을 볼 수 있습니다."
      sub={state.data ? `${number(attempts.length)}건${state.data.truncated ? ' · 최근 기록까지만 집계' : ''}` : undefined}>
      {state.error ? <ErrorState message={state.error} onRetry={state.reload} />
        : !state.data ? <SkeletonRows rows={3} cols={5} />
        : !attempts.length ? <Empty title="예매 시도 이력이 없습니다" />
        : (
          <div className="tbl-wrap"><table className="tbl dense">
            <thead><tr><th>공연</th><th>시작 시각</th><th>결과</th><th>마지막 단계</th><th className="num">단계 수</th><th className="act" /></tr></thead>
            <tbody>{shown.map(a => {
              const opened = openId === a.attemptId
              return (
                <Fragment key={a.attemptId}>
                  <tr>
                    <td><button className="row-toggle" aria-expanded={opened} aria-label="단계별 진행 보기" onClick={() => setOpenId(opened ? '' : a.attemptId)}><ChevronRight size={15} /></button>
                      <b>{concertName(a.concertId)}</b></td>
                    <td className="tnum muted">{dateTimeSec(a.startedAt)}</td>
                    <td>{resultBadge(a)}</td>
                    <td>{STAGE_LABEL[a.stage] ?? a.stage} <span className="muted">· {STATUS_LABEL[a.status] ?? a.status}</span></td>
                    <td className="num muted">{a.events.length}</td>
                    <td className="act"><button className="btn sm" disabled={!a.concertId} onClick={() => onOpenConcert(a.concertId, concertName(a.concertId))}>공연 보기</button></td>
                  </tr>
                  {opened && (
                    <tr className="expanded"><td colSpan={6}>
                      <ol className="timeline inset">{a.events.map(e => {
                        const status = eventStatus(e)
                        return (
                          <li key={e.eventId} className={FAILED_STATUSES.includes(status) ? 'danger' : status === 'SUCCEEDED' ? 'strong' : ''}>
                            <time>{dateTimeSec(e.occurredAt)}</time>
                            <span>{STAGE_LABEL[eventStage(e)] ?? eventStage(e)} <span className="muted">· {STATUS_LABEL[status] ?? status}</span></span>
                          </li>
                        )
                      })}</ol>
                    </td></tr>
                  )}
                </Fragment>
              )
            })}</tbody>
          </table></div>
        )}
      {more}
    </Panel>
  )
}

function BookingPanel({ state }: { state: ReturnType<typeof useApi<PageResponse<BookingHistory>>> }) {
  const { shown, more } = useMore(state.data?.content ?? [], 5)
  return (
    <Panel title="관람 이력" flush hint="티켓 한 장 단위의 예매 · 관람 · 노쇼 · 취소 기록입니다. 기본 Credit 산출에 사용됩니다."
      sub={state.data ? `${number(state.data.totalElements)}건` : undefined}>
      {state.error ? <ErrorState message={state.error} onRetry={state.reload} />
        : !state.data ? <SkeletonRows rows={3} cols={4} />
        : !state.data.content.length ? <Empty title="관람 이력이 없습니다" />
        : (
          <div className="tbl-wrap"><table className="tbl dense">
            <thead><tr><th>공연</th><th>예매 시각</th><th>공연 일시</th><th>상태</th></tr></thead>
            <tbody>{shown.map(b => (
              <tr key={b.historyId}>
                <td title={b.ticketId ? `티켓 ${b.ticketId}` : undefined}><b>{b.concertName ?? b.concertId ?? '—'}</b></td>
                <td className="tnum muted">{dateTimeSec(b.bookedAt)}</td>
                <td className="tnum muted">{dateTime(b.performanceStartsAt)}</td>
                <td><Badge tone={BOOKING_STATUS[b.status]?.tone ?? 'muted'}>{BOOKING_STATUS[b.status]?.label ?? b.status}</Badge></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      {more}
    </Panel>
  )
}

function EventPanel({ state }: { state: ReturnType<typeof useApi<PageResponse<UserEvent>>> }) {
  const { shown, more } = useMore(state.data?.content ?? [], 8)
  return (
    <Panel title="행동 이벤트" flush hint="티켓팅 서비스에서 수집된 이 사용자의 행동을 최근 순으로 보여줍니다."
      sub={state.data ? `${number(state.data.totalElements)}건${state.data.totalElements > state.data.content.length ? ` 중 최근 ${state.data.content.length}건` : ''}` : undefined}>
      {state.error ? <ErrorState message={state.error} onRetry={state.reload} />
        : !state.data ? <SkeletonRows rows={3} cols={2} />
        : !state.data.content.length ? <Empty title="수집된 이벤트가 없습니다" />
        : (
          <ol className="timeline">{shown.map(e => {
            const stage = eventStage(e)
            const status = eventStatus(e)
            const attempt = e.eventType === 'BOOKING_ATTEMPT'
            return (
              <li key={e.eventId} className={attempt && FAILED_STATUSES.includes(status) ? 'danger' : !attempt ? 'strong' : ''}>
                <time>{dateTimeSec(e.occurredAt)}</time>
                <span>{attempt && stage ? <>{STAGE_LABEL[stage] ?? stage} <span className="muted">· {STATUS_LABEL[status] ?? status}</span></> : <b>{EVENT_TEXT[e.eventType] ?? e.eventType}</b>}</span>
              </li>
            )
          })}</ol>
        )}
      {more}
    </Panel>
  )
}
