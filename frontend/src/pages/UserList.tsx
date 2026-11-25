import { useState } from 'react'
import { Search } from 'lucide-react'
import { api } from '../api/client'
import type { AdminUser, DashboardSummary, PageResponse } from '../api/types'
import type { ApiState } from '../hooks'
import { useApi } from '../hooks'
import { COLOR, CreditBar, Empty, ErrorState, Kpis, PageHead, Pager, Panel, RefreshButton, SkeletonRows, StatusBadge, TIER_ORDER, TierBadge, tierInfo } from '../ui'
import type { KpiItem } from '../ui'
import { dateTime, number, percent, score } from '../utils'
import type { Session } from '../utils'
import { USER_SORTS } from './ConcertUsers'

interface Props {
  session: Session
  summary: ApiState<DashboardSummary>
  onOpenUser: (userId: string, label: string) => void
}

const SIZE = 20
interface Filters { keyword: string; tier: string; assessed: string; sort: string }
const DEFAULT: Filters = { keyword: '', tier: '', assessed: '', sort: 'creditScore,asc' }

export default function UserList({ session, summary, onOpenUser }: Props) {
  // 입력 중인 값(draft)과 서버에 적용된 값(applied)을 나눠, 조회를 눌렀을 때만 요청합니다.
  const [draft, setDraft] = useState<Filters>(DEFAULT)
  const [applied, setApplied] = useState<Filters>(DEFAULT)
  const [page, setPage] = useState(0)

  // 검색 · 필터 · 정렬은 모두 서버에서 전체 사용자를 대상으로 처리합니다.
  const list = useApi<PageResponse<AdminUser>>(() => api.users(session.adminKey, {
    serviceId: session.serviceId, page, size: SIZE, sort: applied.sort,
    keyword: applied.keyword || undefined, tier: applied.tier || undefined, assessed: applied.assessed || undefined,
  }), [session.adminKey, session.serviceId, page, applied])

  const apply = (next: Filters) => { setDraft(next); setApplied({ ...next, keyword: next.keyword.trim() }); setPage(0) }
  const s = summary.data
  const tierKeys = s ? [...TIER_ORDER, ...Object.keys(s.usersByTier).filter(t => !TIER_ORDER.includes(t))] : TIER_ORDER
  const filtered = applied.keyword || applied.tier || applied.assessed
  const data = list.data

  const kpis: KpiItem[] = [
    { label: '전체 사용자', value: number(s?.totalUsers), unit: '명' },
    ...tierKeys.map(t => ({
      label: `${tierInfo(t).label} 등급`, value: number(s?.usersByTier[t] ?? 0), unit: '명', sub: percent(s?.usersByTier[t] ?? 0, s?.totalUsers),
      hint: t === 'NORMAL' ? 'Credit 40점 이상' : t === 'SUSPICIOUS' ? 'Credit 20~39점' : t === 'HIGH_RISK' ? 'Credit 19점 이하'
        : t === 'BASE' ? 'Credit이 아직 한 번도 산정되지 않은 사용자' : undefined,
    } as KpiItem)),
    { label: 'AI 미평가', value: number(s?.unassessedUsers), unit: '명', gap: true, sub: percent(s?.unassessedUsers, s?.totalUsers),
      hint: 'AI 평가 결과가 아직 없는 사용자입니다. 위험 등급과는 별개의 분류라 한 사용자가 양쪽에 함께 집계됩니다.' },
  ]

  return (
    <div className="stack">
      <PageHead title="사용자 관리">
        <RefreshButton onClick={() => { summary.reload(); list.reload() }} loading={list.loading} loadedAt={list.loadedAt} />
      </PageHead>

      {summary.error ? <ErrorState inline message={`요약을 불러오지 못했습니다. ${summary.error}`} onRetry={summary.reload} />
        : <Kpis compact loading={!s} items={kpis} />}

      <Panel flush
        title={(
          <form className="filters" onSubmit={e => { e.preventDefault(); apply(draft) }}>
            <span className="input-icon" style={{ minWidth: 280 }}>
              <Search size={15} />
              <input className="input" value={draft.keyword} onChange={e => setDraft({ ...draft, keyword: e.target.value })}
                placeholder="사용자 ID 검색" title="외부 사용자 ID 또는 내부 ID의 일부로 검색합니다." aria-label="사용자 ID 검색" />
            </span>
            <select className="select" aria-label="위험 등급" value={draft.tier} onChange={e => apply({ ...draft, tier: e.target.value })}>
              <option value="">위험 등급 전체</option>
              {tierKeys.map(t => <option key={t} value={t}>{tierInfo(t).label}</option>)}
            </select>
            <select className="select" aria-label="AI 평가 여부" value={draft.assessed} onChange={e => apply({ ...draft, assessed: e.target.value })}>
              <option value="">AI 평가 전체</option>
              <option value="true">AI 평가 완료</option>
              <option value="false">AI 미평가</option>
            </select>
            <select className="select" aria-label="정렬" value={draft.sort} onChange={e => apply({ ...draft, sort: e.target.value })}>
              {USER_SORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
            <button className="btn primary" type="submit">조회</button>
            {filtered && <button className="btn ghost" type="button" onClick={() => apply({ ...DEFAULT, sort: draft.sort })}>초기화</button>}
          </form>
        )}
        foot={data && data.totalElements > 0
          ? <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} hasNext={data.hasNext} onPage={setPage} unit="명" />
          : undefined}>
        {list.error ? <ErrorState message={list.error} onRetry={list.reload} />
          : !data ? <SkeletonRows rows={8} cols={7} />
          : !data.content.length ? (
            <Empty title={filtered ? '조건에 맞는 사용자가 없습니다' : '등록된 사용자가 없습니다'}>
              {filtered ? '검색어나 필터를 바꿔 다시 조회해 주세요.' : '티켓팅 서비스가 사용자를 등록하면 여기에 표시됩니다.'}</Empty>
          ) : (
            <div className="tbl-wrap" style={{ opacity: list.loading ? 0.6 : 1 }}>
              <table className="tbl">
                <thead><tr>
                  <th>사용자 ID</th><th>위험 등급</th><th>Credit</th>
                  <th className="num" title="가장 최근에 성공한 AI 평가의 점수입니다. 0.5 이상이면 이상 행동으로 판정합니다.">AI 이상 점수</th>
                  <th title="가장 최근 AI 평가 시도의 결과입니다.">AI 평가 상태</th><th>최근 평가</th><th className="act" />
                </tr></thead>
                <tbody>{data.content.map(user => (
                  <tr key={user.userId}>
                    <td title={`내부 ID ${user.userId}`}><b>{user.externalUserId}</b></td>
                    <td><TierBadge tier={user.tier} /></td>
                    <td><CreditBar value={user.creditScore} tier={user.tier} /></td>
                    <td className="num">{user.anomalyScore == null ? <span className="na">—</span>
                      : user.anomalyScore >= 0.5 ? <b style={{ color: COLOR.dangerText }}>{score(user.anomalyScore)}</b> : score(user.anomalyScore)}</td>
                    <td><StatusBadge status={user.assessmentStatus} /></td>
                    <td className="tnum muted">{dateTime(user.lastAssessedAt)}</td>
                    <td className="act"><button className="btn sm" onClick={() => onOpenUser(user.userId, user.externalUserId)}>상세</button></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
      </Panel>
    </div>
  )
}
