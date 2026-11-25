import { useState } from 'react'
import { api } from '../api/client'
import type { Concert, DashboardSummary, PageResponse } from '../api/types'
import { Donut, HBars, Histogram, StackBar } from '../charts'
import type { ApiState } from '../hooks'
import { useApi } from '../hooks'
import {
  COLOR, Empty, ErrorState, Kpis, PageHead, Panel, POLICY_ORDER, RefreshButton, SkeletonBlock, SkeletonRows, TIER_BOUNDS, TIER_ORDER, Tabs,
  policyInfo, tierInfo,
} from '../ui'
import { dateTime, decimal, number, percent } from '../utils'
import type { Session } from '../utils'
import { CaptchaResult, SanctionTable } from './ConcertDetail'

interface Props {
  session: Session
  summary: ApiState<DashboardSummary>
  concerts: ApiState<PageResponse<Concert>>
  onOpenConcert: (concertId: string, name?: string) => void
  onAllConcerts: () => void
}

type Tab = 'overview' | 'stats'
/** Credit(정수 0~100) 10점 구간. 마지막 구간만 100을 포함합니다. */
const CREDIT_BINS = Array.from({ length: 10 }, (_, i) => ({ min: i * 10, max: i === 9 ? 100 : i * 10 + 9 }))
/** 이상 점수(0~1) 0.1 구간. 서버의 범위 조건이 양끝 포함이라 상한을 구간 바로 아래 값으로 둡니다. */
const SCORE_BINS = Array.from({ length: 10 }, (_, i) => ({ min: i / 10, max: i === 9 ? 1 : (i + 1) / 10 - 1e-9 }))
const EVENT_TEXT: Record<string, string> = { BOOKING: '예매 완료', CANCEL: '예매 취소', CHECK_IN: '입장', BOOKING_ATTEMPT: '예매 진행 단계' }
const EXPECTED_HINT = '현재 Credit과 AI 이상 점수 기준으로, 지금 예매를 요청하면 받게 될 조치입니다. 실제로 적용된 정책과는 다릅니다.'

export default function Dashboard({ session, summary, concerts, onOpenConcert, onAllConcerts }: Props) {
  const [tab, setTab] = useState<Tab>('overview')
  const [statsOpened, setStatsOpened] = useState(false)
  const open = (next: Tab) => { setTab(next); if (next === 'stats') setStatsOpened(true) }
  const s = summary.data

  // 분포는 상세 통계를 처음 열 때만 불러옵니다. 범위 조건을 건 목록 조회의 전체 건수만 사용합니다.
  const count = (query: Record<string, number>) =>
    api.users(session.adminKey, { serviceId: session.serviceId, page: 0, size: 1, ...query }).then(p => p.totalElements)
  const credit = useApi<number[]>(() => Promise.all(CREDIT_BINS.map(b => count({ minCreditScore: b.min, maxCreditScore: b.max }))),
    [session.adminKey, session.serviceId, summary.loadedAt], { skip: !statsOpened })
  const anomaly = useApi<number[]>(() => Promise.all(SCORE_BINS.map(b => count({ minAnomalyScore: b.min, maxAnomalyScore: b.max }))),
    [session.adminKey, session.serviceId, summary.loadedAt], { skip: !statsOpened })

  const reload = () => { summary.reload(); concerts.reload() }
  const tierKeys = s ? [...TIER_ORDER.filter(t => t in s.usersByTier), ...Object.keys(s.usersByTier).filter(t => !TIER_ORDER.includes(t))] : []
  const creditTotal = credit.data?.reduce((a, b) => a + b, 0) ?? 0
  const anomalyTotal = anomaly.data?.reduce((a, b) => a + b, 0) ?? 0
  const binColor = (min: number) => min >= TIER_BOUNDS.normal ? COLOR.ink : min >= TIER_BOUNDS.suspicious ? COLOR.warn : COLOR.danger
  const sanctions = s?.sanctions

  return (
    <div>
      <PageHead title="대시보드">
        <RefreshButton onClick={reload} loading={summary.loading} loadedAt={summary.loadedAt} />
      </PageHead>
      <Tabs<Tab> tabs={[['overview', '운영 현황'], ['stats', '상세 통계']]} value={tab} onChange={open} />

      {summary.error ? <div className="panel"><ErrorState message={summary.error} onRetry={summary.reload} /></div>
        : tab === 'overview' ? (
          <div className="stack tab-panel" key="overview">
            <Kpis loading={!s} items={[
              { label: '전체 사용자', value: number(s?.totalUsers), unit: '명', sub: `활성 ${number(s?.activeUsers)} · 정지 ${number(s?.suspendedUsers)}` },
              { label: '이상 탐지 사용자', value: number(s?.anomalyUsers), unit: '명', sub: `AI 평가 완료 ${number(s?.assessedUsers)}명 중 ${percent(s?.anomalyUsers, s?.assessedUsers)}`,
                hint: '가장 최근 AI 평가에서 이상 행동으로 판정된 사용자 수입니다. 아직 평가되지 않은 사용자는 포함하지 않습니다.' },
              sanctions?.recorded
                ? { label: '실제 정책 적용 사용자', value: number(sanctions.appliedUserCount), unit: '명', sub: '티켓팅 서비스 보고 기준',
                  hint: '티켓팅 서비스가 CAPTCHA 인증 · 대기열 후순위 · 예매 제한을 실제로 적용했다고 보고한 사용자 수입니다.' }
                : { label: '실제 정책 적용 사용자', value: '기록 없음', text: true, na: true, sub: '적용 결과가 아직 보고되지 않음',
                  hint: '티켓팅 서비스가 정책 적용 결과를 보고한 적이 없습니다. 적용 0명과는 다른 상태입니다.' },
              { label: '평균 Credit', value: decimal(s?.averageCreditScore, 1), unit: '점', na: s?.averageCreditScore == null, sub: '0~100 · 높을수록 신뢰' },
            ]} />

            <div className="grid cols-2">
              <Panel title="AI 탐지 및 평가 현황" hint="전체 사용자를 AI 평가 결과로 나눈 것입니다. 미평가 사용자는 정상으로도 이상으로도 보지 않습니다.">
                {!s ? <SkeletonBlock h={150} /> : !s.totalUsers ? <Empty title="등록된 사용자가 없습니다" /> : (
                  <>
                    <StackBar unit="명" data={[
                      { name: '이상 탐지', value: s.anomalyUsers, color: COLOR.danger },
                      { name: '이상 아님', value: s.assessedUsers - s.anomalyUsers, color: COLOR.ink },
                      { name: '미평가', value: s.unassessedUsers, color: COLOR.inkFaint },
                    ]} />
                    {s.insufficientDataUsers > 0 &&
                      <p className="note" style={{ marginTop: 12 }}>최근 평가에서 데이터가 부족했던 사용자 {number(s.insufficientDataUsers)}명</p>}
                  </>
                )}
              </Panel>
              <Panel title="실제 정책 적용 현황" hint="티켓팅 서비스가 실제로 적용했다고 보고한 기록입니다. 한 사용자가 여러 정책을 받을 수 있어 정책별 합계는 전체와 다를 수 있습니다." flush>
                {!s ? <div style={{ padding: 20 }}><SkeletonBlock h={150} /></div> : <SanctionTable sanctions={s.sanctions} />}
              </Panel>
            </div>

            <Panel title="최근 공연" flush actions={<button className="btn" onClick={onAllConcerts}>전체 공연</button>}>
              {concerts.error ? <ErrorState message={concerts.error} onRetry={concerts.reload} />
                : !concerts.data ? <SkeletonRows rows={4} cols={5} />
                : !concerts.data.content.length ? <Empty title="연동된 공연이 없습니다">티켓팅 서비스가 공연을 등록하면 여기에 표시됩니다.</Empty>
                : (
                  <div className="tbl-wrap"><table className="tbl">
                    <thead><tr><th>공연명</th><th>공연 일시</th><th>장소</th>
                      <th className="num" title="이 공연에 예매를 시도한 사용자 중 이상 행동으로 판정된 사용자 수">이상 탐지 사용자</th><th className="act" /></tr></thead>
                    <tbody>{concerts.data.content.slice(0, 6).map(c => (
                      <tr key={c.concertId}>
                        <td><b>{c.name}</b></td>
                        <td className="tnum muted">{dateTime(c.startsAt)}</td>
                        <td className="muted">{c.venue || '—'}</td>
                        <td className="num">{c.anomalyUserCount == null ? <span className="na">—</span>
                          : c.anomalyUserCount > 0 ? <b style={{ color: COLOR.dangerText }}>{number(c.anomalyUserCount)}명</b> : <span className="muted">0명</span>}</td>
                        <td className="act"><button className="btn sm" onClick={() => onOpenConcert(c.concertId, c.name)}>상세</button></td>
                      </tr>
                    ))}</tbody>
                  </table></div>
                )}
            </Panel>
          </div>
        ) : (
          <div className="stack tab-panel" key="stats">
            <div className="grid cols-2">
              <Panel title="위험 등급별 사용자" hint="Credit 점수로 정해지는 등급입니다. AI 평가 여부와는 별개의 분류입니다.">
                {!s ? <SkeletonBlock h={168} /> : (
                  <Donut unit="명" centerLabel="전체" empty="등록된 사용자가 없습니다."
                    data={tierKeys.map(t => ({ name: tierInfo(t).label, value: s.usersByTier[t], color: tierInfo(t).color }))} />
                )}
              </Panel>
              <Panel title="예상 정책별 사용자" hint={EXPECTED_HINT}>
                {!s ? <SkeletonBlock h={168} /> : !s.totalUsers ? <Empty title="등록된 사용자가 없습니다" /> : (
                  <HBars unit="명" total={s.totalUsers} max={s.totalUsers} data={POLICY_ORDER.map(a => ({
                    name: policyInfo(a).label, value: s.usersByExpectedPolicy?.[a] ?? 0, color: policyInfo(a).color,
                  }))} />
                )}
              </Panel>
            </div>

            <div className="grid cols-2">
              <Panel title="Credit 분포" sub={credit.data ? `${number(creditTotal)}명 · 10점 구간` : undefined}
                hint="40점 이상 정상, 20~39점 의심, 19점 이하 위험 구간입니다.">
                {credit.error ? <ErrorState message={credit.error} onRetry={credit.reload} />
                  : !credit.data ? <SkeletonBlock />
                  : !creditTotal ? <div className="chart-empty" style={{ height: 220 }}>Credit이 산정된 사용자가 없습니다.</div>
                  : (
                    <>
                      <Histogram unit="명" xLabel="Credit"
                        data={CREDIT_BINS.map((b, i) => ({ label: `${b.min}–${b.max}`, count: credit.data![i], color: binColor(b.min) }))} />
                      <div className="legend">
                        <span><i style={{ background: COLOR.danger }} />위험 구간</span>
                        <span><i style={{ background: COLOR.warn }} />의심 구간</span>
                        <span><i style={{ background: COLOR.ink }} />정상 구간</span>
                      </div>
                    </>
                  )}
              </Panel>
              <Panel title="AI 이상 점수 분포" sub={anomaly.data ? `AI 평가 완료 ${number(anomalyTotal)}명 · 0.1 구간` : undefined}
                hint="이상 점수는 0~1 사이 값이며 0.5 이상이면 이상 행동으로 판정합니다. 미평가 사용자는 제외합니다.">
                {anomaly.error ? <ErrorState message={anomaly.error} onRetry={anomaly.reload} />
                  : !anomaly.data ? <SkeletonBlock />
                  : !anomalyTotal ? <div className="chart-empty" style={{ height: 220 }}>AI 평가를 마친 사용자가 없습니다.</div>
                  : (
                    <>
                      <Histogram unit="명" xLabel="이상 점수"
                        data={SCORE_BINS.map((b, i) => ({ label: `${b.min.toFixed(1)}–${i === 9 ? '1.0' : ((i + 1) / 10).toFixed(1)}`, count: anomaly.data![i], color: b.min >= 0.5 ? COLOR.danger : COLOR.ink }))} />
                      <div className="legend">
                        <span><i style={{ background: COLOR.ink }} />이상 아님</span>
                        <span><i style={{ background: COLOR.danger }} />이상 탐지 (0.5 이상)</span>
                      </div>
                    </>
                  )}
              </Panel>
            </div>

            <div className="grid cols-2">
              <Panel title="CAPTCHA 인증 결과" hint="같은 인증에서 실패한 뒤 통과할 수 있어, 통과와 실패의 합이 발급 수와 다를 수 있습니다.">
                {!s ? <SkeletonBlock h={120} /> : <CaptchaResult sanctions={s.sanctions} />}
              </Panel>
              <Panel title="행동 이벤트 수집" sub={s ? `누적 ${number(s.totalEvents)}건` : undefined}>
                {!s ? <SkeletonBlock h={120} /> : !s.totalEvents ? <Empty title="수집된 이벤트가 없습니다" /> : (
                  <HBars unit="건" total={s.totalEvents} data={Object.entries(s.eventsByType).map(([type, value]) => ({
                    name: EVENT_TEXT[type] ?? type, value, color: COLOR.inkSoft,
                  }))} />
                )}
              </Panel>
            </div>
          </div>
        )}
    </div>
  )
}
