import { useState } from 'react'
import { api } from '../api/client'
import type { ConcertUser, PageResponse } from '../api/types'
import { useApi } from '../hooks'
import { COLOR, CreditBar, Empty, ErrorState, Pager, Panel, SkeletonRows, StatusBadge, TierBadge } from '../ui'
import { dateTime, score } from '../utils'
import type { Session } from '../utils'

/** 서버가 지원하는 정렬만 제공합니다. 미평가 사용자는 이상 점수 정렬에서 항상 뒤에 옵니다. */
export const USER_SORTS: [string, string][] = [
  ['creditScore,asc', 'Credit 낮은 순'],
  ['creditScore,desc', 'Credit 높은 순'],
  ['anomalyScore,desc', '이상 점수 높은 순'],
  ['anomalyScore,asc', '이상 점수 낮은 순'],
  ['createdAt,desc', '최근 등록 순'],
]

interface Props {
  session: Session
  concertId: string
  size: number
  title: string
  refreshKey?: number
  onOpenUser: (userId: string, label: string) => void
  onAll?: () => void
}

/** 공연 참여 사용자(이 공연에 예매를 시도한 사용자) 목록. 공연 상세의 탭과 전체 목록 화면이 함께 씁니다. */
export default function ConcertUsers({ session, concertId, size, title, refreshKey = 0, onOpenUser, onAll }: Props) {
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState('creditScore,asc')
  const list = useApi<PageResponse<ConcertUser>>(
    () => api.concertUsers(session.adminKey, session.serviceId, concertId, { page, size, sort }),
    [session.adminKey, session.serviceId, concertId, page, size, sort, refreshKey])
  const data = list.data

  return (
    <Panel title={title} flush hint="이 공연에 예매를 시도한 사용자입니다. Credit · 위험 등급 · AI 이상 점수는 공연과 무관한 사용자의 현재 값입니다."
      actions={(
        <div className="filters">
          <select className="select" aria-label="정렬" value={sort} onChange={e => { setSort(e.target.value); setPage(0) }}>
            {USER_SORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
          {onAll && <button className="btn" onClick={onAll}>크게 보기</button>}
        </div>
      )}
      foot={data && data.totalElements > 0
        ? <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} hasNext={data.hasNext} onPage={setPage} unit="명" />
        : undefined}>
      {list.error ? <ErrorState message={list.error} onRetry={list.reload} />
        : !data ? <SkeletonRows rows={Math.min(size, 6)} cols={7} />
        : !data.content.length ? <Empty title="참여 사용자가 없습니다">이 공연에 예매를 시도한 사용자가 아직 없습니다.</Empty>
        : (
          <div className="tbl-wrap" style={{ opacity: list.loading ? 0.6 : 1 }}>
            <table className="tbl">
              <thead><tr>
                <th>사용자 ID</th><th>위험 등급</th><th>Credit</th><th className="num">AI 이상 점수</th>
                <th>AI 평가 상태</th><th>마지막 예매 시도</th><th className="act" />
              </tr></thead>
              <tbody>{data.content.map(({ user, lastBookingAttemptAt }) => (
                <tr key={user.userId}>
                  <td title={`내부 ID ${user.userId}`}><b>{user.externalUserId}</b></td>
                  <td><TierBadge tier={user.tier} /></td>
                  <td><CreditBar value={user.creditScore} tier={user.tier} /></td>
                  <td className="num">{user.anomalyScore == null ? <span className="na">—</span>
                    : user.anomalyScore >= 0.5 ? <b style={{ color: COLOR.dangerText }}>{score(user.anomalyScore)}</b> : score(user.anomalyScore)}</td>
                  <td><StatusBadge status={user.assessmentStatus} /></td>
                  <td className="tnum muted">{dateTime(lastBookingAttemptAt)}</td>
                  <td className="act"><button className="btn sm" onClick={() => onOpenUser(user.userId, user.externalUserId)}>상세</button></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
    </Panel>
  )
}
