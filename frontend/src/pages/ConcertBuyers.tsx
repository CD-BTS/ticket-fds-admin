import { useEffect, useState } from 'react'
import { ChevronLeft, ChevronRight, LoaderCircle } from 'lucide-react'
import { api } from '../api/client'
import type { ConcertUser, PageResponse } from '../api/types'
import { explainError, number, score } from '../utils'
import type { Session } from '../utils'

interface Props {
  session: Session
  concertId: string
  concertName: string
  onOpenUser: (userId: string) => void
  onError: (message: string) => void
}

const SIZE = 20

export default function ConcertBuyers({ session, concertId, concertName, onOpenUser, onError }: Props) {
  const [data, setData] = useState<PageResponse<ConcertUser> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true)
    api.concertUsers(session.adminKey, session.serviceId, concertId, { page, size: SIZE, sort: 'creditScore,desc' })
      .then(d => alive && setData(d))
      .catch(e => alive && onError(`예매자 목록 조회 실패: ${explainError(e)}`))
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.adminKey, session.serviceId, concertId, page])

  return (
    <section className="page">
      <h1 className="center-title"><span>({concertName})</span> 예매자 목록</h1>
      {loading && !data ? <p className="state-line"><LoaderCircle className="spin" size={18} /> 불러오는 중입니다.</p> : (
        <div className="table-card">
          <div className="table-scroll">
            <table>
              <thead><tr><th>이름 (아이디)</th><th>등급</th><th>크레딧</th><th>이상 점수</th><th /></tr></thead>
              <tbody>
                {data?.content.map(({ user }) => (
                  <tr key={user.userId}>
                    <td><b>{user.externalUserId}</b><small className="mono">{user.userId}</small></td>
                    <td><span className="tier">{user.tier ?? '—'}</span></td>
                    <td>{number(user.creditScore)}</td>
                    <td>{score(user.anomalyScore)}</td>
                    <td className="right"><button className="btn outline small" onClick={() => onOpenUser(user.userId)}>조회</button></td>
                  </tr>
                ))}
                {!data?.content.length && <tr><td colSpan={5} className="empty-line">이 공연에 예매를 시도한 사용자가 없습니다.</td></tr>}
              </tbody>
            </table>
          </div>
          <Pager data={data} page={page} onPage={setPage} />
        </div>
      )}
    </section>
  )
}

export function Pager({ data, page, onPage }: { data: PageResponse<unknown> | null; page: number; onPage: (p: number) => void }) {
  if (!data || data.totalElements === 0) return null
  return (
    <div className="pager">
      <span>총 {number(data.totalElements)}명 · {page + 1} / {Math.max(1, data.totalPages)} 페이지</span>
      <div>
        <button className="btn outline small" disabled={page <= 0} onClick={() => onPage(page - 1)}><ChevronLeft size={14} />이전</button>
        <button className="btn outline small" disabled={!data.hasNext} onClick={() => onPage(page + 1)}>다음<ChevronRight size={14} /></button>
      </div>
    </div>
  )
}
