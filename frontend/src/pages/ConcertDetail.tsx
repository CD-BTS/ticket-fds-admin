import { useEffect, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { api } from '../api/client'
import type { ConcertDetail as Detail, SanctionStatistics } from '../api/types'
import { Donut, HBars, StackBar } from '../charts'
import { useApi } from '../hooks'
import {
  COLOR, Empty, ErrorState, Kpis, PageHead, Panel, POLICY_ORDER, RefreshButton, Skeleton, SkeletonBlock, TIER_ORDER, Tabs, policyInfo, tierInfo,
} from '../ui'
import { dateTime, decimal, number, percent } from '../utils'
import type { Session } from '../utils'
import { STAGE_LABEL, STATUS_LABEL } from './attempts'
import ConcertTrendChart from './ConcertTrendChart'
import ConcertUsers from './ConcertUsers'

interface Props {
  session: Session
  concertId: string
  onLoaded: (name: string) => void
  onBack: () => void
  onBuyers: () => void
  onOpenUser: (userId: string, label: string) => void
}

type Tab = 'overview' | 'booking' | 'risk' | 'users'

/** 실패한 시도의 마지막 단계 · 상태를 읽기 쉬운 문구로 바꿉니다. 알 수 없는 값은 그대로 둡니다. */
const failureLabel = (key: string) => {
  const [stage, status] = key.split('/')
  return `${STAGE_LABEL[stage] ?? stage} 단계에서 ${STATUS_LABEL[status] ?? status ?? ''}`.trim()
}

export default function ConcertDetail({ session, concertId, onLoaded, onBack, onBuyers, onOpenUser }: Props) {
  const [tab, setTab] = useState<Tab>('overview')
  const [riskOpened, setRiskOpened] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const open = (next: Tab) => { setTab(next); if (next === 'risk') setRiskOpened(true) }

  const detail = useApi<Detail>(() => api.concert(session.adminKey, session.serviceId, concertId),
    [session.adminKey, session.serviceId, concertId, refreshKey])
  // 참여 사용자의 위험 등급 분포: 등급 필터를 건 목록 조회의 전체 건수만 사용하며, 탭을 처음 열 때 불러옵니다.
  const tiers = useApi<number[]>(() => Promise.all(TIER_ORDER.map(tier =>
    api.concertUsers(session.adminKey, session.serviceId, concertId, { tier, page: 0, size: 1 }).then(p => p.totalElements))),
  [session.adminKey, session.serviceId, concertId, refreshKey], { skip: !riskOpened })

  const d = detail.data
  useEffect(() => { if (d) onLoaded(d.concert.name) }, [d, onLoaded])

  if (detail.error) {
    return (
      <>
        <PageHead title="공연 상세"><button className="btn" onClick={onBack}><ArrowLeft size={14} />공연 목록</button></PageHead>
        <div className="panel"><ErrorState message={detail.error} onRetry={() => setRefreshKey(k => k + 1)} /></div>
      </>
    )
  }

  const participants = d?.bookingAttemptUserCount ?? 0
  const sanctions = d?.sanctions
  const failures = d ? Object.entries(d.failureBreakdown ?? {}).sort((a, b) => b[1] - a[1]) : []
  const tierCounts = tiers.data
  const tierOther = tierCounts && d ? participants - tierCounts.reduce((s, n) => s + n, 0) : 0
  const results = d ? [
    { name: '성공', value: d.bookingSuccessCount, color: COLOR.ink },
    { name: '실패', value: d.bookingFailureCount, color: COLOR.danger },
    { name: '미종료', value: d.bookingInProgressCount, color: COLOR.inkFaint, hint: '아직 성공도 실패도 아닌 진행 중인 시도' },
  ] : []

  return (
    <div>
      <PageHead title={d ? d.concert.name : <Skeleton w={320} h={28} />}
        sub={d ? `${dateTime(d.concert.startsAt)} ~ ${dateTime(d.concert.endsAt).slice(11)}${d.concert.venue ? ` · ${d.concert.venue}` : ''}` : undefined}>
        <RefreshButton onClick={() => setRefreshKey(k => k + 1)} loading={detail.loading} loadedAt={detail.loadedAt} />
        <button className="btn" onClick={onBack}><ArrowLeft size={14} />공연 목록</button>
      </PageHead>

      <Kpis loading={!d} items={[
        { label: '전체 예매 시도', value: number(d?.bookingAttemptCount), unit: '건', sub: `참여 사용자 ${number(d?.bookingAttemptUserCount)}명`,
          hint: '예매를 시작한 시도의 수입니다. 한 사용자가 여러 번 시도하면 각각 셉니다. 성공 + 실패 + 미종료와 같습니다.' },
        { label: '예매 성공', value: number(d?.bookingSuccessCount), unit: '건', sub: `성공률 ${percent(d?.bookingSuccessCount, d?.bookingAttemptCount)}`,
          hint: '성공률 = 예매 성공 ÷ 전체 예매 시도' },
        { label: '예매 실패', value: number(d?.bookingFailureCount), unit: '건', sub: `전체의 ${percent(d?.bookingFailureCount, d?.bookingAttemptCount)}`,
          hint: '거절 · 실패 · 이탈 · 만료로 끝난 시도입니다.' },
        { label: '미종료 예매', value: number(d?.bookingInProgressCount), unit: '건', sub: `전체의 ${percent(d?.bookingInProgressCount, d?.bookingAttemptCount)}`,
          hint: '아직 성공도 실패도 아닌 진행 중인 시도입니다.' },
        { label: '이상 탐지 사용자', value: number(d?.anomalyUserCount), unit: '명', sub: `참여 사용자의 ${percent(d?.anomalyUserCount, participants)}`,
          hint: '이 공연에 예매를 시도한 사용자 중, 가장 최근 AI 평가에서 이상 행동으로 판정된 사용자 수입니다.' },
        sanctions?.recorded
          ? { label: '실제 정책 적용 사용자', value: number(sanctions.appliedUserCount), unit: '명', sub: '티켓팅 서비스 보고 기준',
            hint: '티켓팅 서비스가 이 공연에서 정책을 실제로 적용했다고 보고한 사용자 수입니다.' }
          : { label: '실제 정책 적용 사용자', value: '기록 없음', text: true, na: true, sub: '적용 결과가 아직 보고되지 않음',
            hint: '티켓팅 서비스가 정책 적용 결과를 보고한 적이 없습니다. 적용 0명과는 다른 상태입니다.' },
      ]} />

      <div style={{ marginTop: 20 }}>
        <Tabs<Tab> value={tab} onChange={open}
          tabs={[['overview', '개요'], ['booking', '예매 분석'], ['risk', '탐지 및 정책'], ['users', '참여 사용자', d ? number(participants) : undefined]]} />
      </div>

      {tab === 'overview' && (
        <div className="stack tab-panel" key="overview">
          <ConcertTrendChart session={session} concertId={concertId} refreshKey={refreshKey} />
          <Panel title="예매 결과 요약" actions={<button className="btn link" onClick={() => open('booking')}>예매 분석 보기</button>}>
            {!d ? <SkeletonBlock h={80} /> : !d.bookingAttemptCount ? <Empty title="예매 시도가 없습니다" /> : <StackBar unit="건" data={results} />}
          </Panel>
        </div>
      )}

      {tab === 'booking' && (
        <div className="stack tab-panel" key="booking">
          <div className="grid cols-2">
            <Panel title="예매 결과" hint="예매 시도 한 건이 마지막에 어떤 상태로 끝났는지 기준입니다.">
              {!d ? <SkeletonBlock h={168} /> : <Donut unit="건" centerLabel="전체 시도" empty="예매 시도가 없습니다." data={results} />}
            </Panel>
            <Panel title="예매 실패 원인" hint="실패한 시도가 마지막으로 머문 단계와 그때의 상태입니다.">
              {!d ? <SkeletonBlock h={168} />
                : !failures.length ? <Empty title="실패한 예매 시도가 없습니다" />
                : <HBars unit="건" total={d.bookingFailureCount} data={failures.map(([key, value]) => ({ name: failureLabel(key), value, color: COLOR.danger }))} />}
            </Panel>
          </div>
          <Panel title="예매 완료 · 취소 · 입장" hint="예매 시도와는 세는 단위가 다릅니다. 예매 완료와 취소는 예매 건 기준, 입장은 티켓(장) 기준입니다.">
            {!d ? <SkeletonBlock h={60} /> : (
              <div className="summary-line">
                <span>예매 완료<b>{number(d.bookingCount)}건</b></span>
                <span>예매 취소<b>{number(d.cancelCount)}건</b></span>
                <span>입장 완료<b>{number(d.checkInCount)}장</b></span>
              </div>
            )}
          </Panel>
        </div>
      )}

      {tab === 'risk' && (
        <div className="stack tab-panel" key="risk">
          <div className="grid cols-2">
            <Panel title="AI 평가 및 이상 탐지" hint="이 공연에 예매를 시도한 사용자를 기준으로 합니다. AI 평가는 사용자 단위라 다른 공연에서의 행동도 반영되며, 미평가 사용자는 정상으로도 이상으로도 보지 않습니다.">
              {!d ? <SkeletonBlock h={180} /> : !participants ? <Empty title="참여 사용자가 없습니다" /> : (
                <>
                  <StackBar unit="명" data={[
                    { name: '이상 탐지', value: d.anomalyUserCount, color: COLOR.danger },
                    { name: '이상 아님', value: d.assessedUserCount - d.anomalyUserCount, color: COLOR.ink },
                    { name: '미평가', value: d.unassessedUserCount, color: COLOR.inkFaint },
                  ]} />
                  <div className="summary-line" style={{ marginTop: 16, paddingTop: 14, borderTop: '1px solid var(--border)' }}>
                    <span title={`이상 탐지 ${d.anomalyUserCount}명 ÷ AI 평가 완료 ${d.assessedUserCount}명`}>평가 완료 사용자 중 이상 탐지<b>{percent(d.anomalyUserCount, d.assessedUserCount)}</b></span>
                    <span>평균 Credit<b>{decimal(d.averageCreditScore, 1)}{d.averageCreditScore != null && '점'}</b></span>
                  </div>
                </>
              )}
            </Panel>
            <Panel title="참여 사용자 위험 등급" hint="Credit 점수로 정해지는 등급입니다. AI 평가 여부와는 별개의 분류입니다.">
              {tiers.error ? <ErrorState message={tiers.error} onRetry={tiers.reload} />
                : !d || !tierCounts ? <SkeletonBlock h={168} /> : (
                  <Donut unit="명" centerLabel="참여 사용자" empty="참여 사용자가 없습니다." data={[
                    ...TIER_ORDER.map((tier, i) => ({ name: tierInfo(tier).label, value: tierCounts[i], color: tierInfo(tier).color })),
                    ...(tierOther > 0 ? [{ name: '미산정', hint: 'Credit이 아직 산정되지 않은 사용자', value: tierOther, color: COLOR.track }] : []),
                  ]} />
                )}
            </Panel>
          </div>

          <div className="grid cols-2">
            <Panel title="예상 정책" hint="참여 사용자의 현재 Credit과 AI 이상 점수 기준으로, 지금 예매를 요청하면 받게 될 조치입니다. 실제로 적용된 정책과는 다릅니다.">
              {!d ? <SkeletonBlock h={180} /> : !participants ? <Empty title="참여 사용자가 없습니다" /> : (
                <HBars unit="명" total={participants} max={participants} data={POLICY_ORDER.map(action => ({
                  name: policyInfo(action).label, value: d.usersByExpectedPolicy?.[action] ?? 0, color: policyInfo(action).color,
                }))} />
              )}
            </Panel>
            <Panel title="실제 정책 적용" flush hint="티켓팅 서비스가 실제로 적용했다고 보고한 기록입니다. 한 사용자가 여러 정책을 받을 수 있어 정책별 합계는 전체와 다를 수 있습니다.">
              {!d ? <div style={{ padding: 20 }}><SkeletonBlock h={150} /></div> : <SanctionTable sanctions={d.sanctions} />}
            </Panel>
          </div>

          <Panel title="CAPTCHA 인증 결과" hint="같은 인증에서 실패한 뒤 통과할 수 있어, 통과와 실패의 합이 발급 수와 다를 수 있습니다.">
            {!d ? <SkeletonBlock h={120} /> : <CaptchaResult sanctions={d.sanctions} />}
          </Panel>
        </div>
      )}

      {tab === 'users' && (
        <div className="tab-panel" key="users">
          <ConcertUsers session={session} concertId={concertId} size={10} refreshKey={refreshKey} title="참여 사용자" onOpenUser={onOpenUser} onAll={onBuyers} />
        </div>
      )}
    </div>
  )
}

/** 실제 적용 정책(티켓팅 서비스 보고 기준). 기록이 없으면 0으로 바꾸지 않고 "기록 없음"으로 보여줍니다. */
export function SanctionTable({ sanctions }: { sanctions: SanctionStatistics }) {
  if (!sanctions.recorded) {
    return <Empty title="정책 적용 기록 없음">티켓팅 서비스가 적용 결과를 아직 보고하지 않았습니다. 적용 0건과는 다른 상태입니다.</Empty>
  }
  const byPolicy = sanctions.byPolicy ?? {}
  const rows = [...['REQUIRE_CAPTCHA', 'DELAY', 'RESTRICT'], ...Object.keys(byPolicy).filter(k => !POLICY_ORDER.includes(k))]
  return (
    <table className="tbl dense">
      <thead><tr><th>정책</th><th className="num">적용 사용자</th>
        <th className="num" title="정책 결정 건수입니다. 같은 결정의 CAPTCHA 발급 · 통과 · 실패는 한 건으로 셉니다.">적용 건수</th></tr></thead>
      <tbody>
        {rows.map(action => {
          const row = byPolicy[action]
          return (
            <tr key={action} data-policy={action}>
              <td>{policyInfo(action).label}</td>
              <td className="num">{row?.userCount ? <b>{number(row.userCount)}명</b> : <span className="na">0명</span>}</td>
              <td className="num">{row?.applicationCount ? `${number(row.applicationCount)}건` : <span className="na">0건</span>}</td>
            </tr>
          )
        })}
        <tr data-policy="TOTAL"><td><b>전체 (중복 제외)</b></td><td className="num"><b>{number(sanctions.appliedUserCount)}명</b></td><td /></tr>
      </tbody>
    </table>
  )
}

export function CaptchaResult({ sanctions }: { sanctions: SanctionStatistics }) {
  if (!sanctions.recorded) return <Empty title="정책 적용 기록 없음">CAPTCHA 인증 결과가 아직 보고되지 않았습니다.</Empty>
  const captcha = sanctions.captcha
  if (!captcha) return <Empty title="CAPTCHA 인증 기록이 없습니다" />
  return (
    <HBars unit="건" max={Math.max(1, captcha.challenged)} data={[
      { name: '발급', value: captcha.challenged, color: COLOR.ink },
      { name: '통과', value: captcha.passed, color: COLOR.inkSoft },
      { name: '실패', value: captcha.failed, color: COLOR.danger },
    ]} />
  )
}
