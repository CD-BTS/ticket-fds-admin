import { useEffect, useRef, useState } from 'react'
import { LoaderCircle } from 'lucide-react'
import { api } from '../api/client'
import type { AdminUser, PageResponse } from '../api/types'
import { explainError, looksLikeUserId, number, score } from '../utils'
import type { Session } from '../utils'
import { Pager } from './ConcertBuyers'

interface Props {
  session: Session
  tiers: string[]
  onOpenUser: (userId: string) => void
  onError: (message: string) => void
}

const SIZE = 20

export default function UserList({ session, tiers, onOpenUser, onError }: Props) {
  const [text, setText] = useState('')
  const [tier, setTier] = useState('')
  const applied = useRef({ text: '', tier: '' })
  const [data, setData] = useState<PageResponse<AdminUser> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    const { text: q, tier: t } = applied.current
    setLoading(true)
    api.users(session.adminKey, {
      serviceId: session.serviceId, page, size: SIZE, sort: 'creditScore,desc',
      tier: t || undefined,
      userId: q && looksLikeUserId(q) ? q : undefined,
      externalUserId: q && !looksLikeUserId(q) ? q : undefined,
    })
      .then(d => alive && setData(d))
      .catch(e => alive && onError(`사용자 목록 조회 실패: ${explainError(e)}`))
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.adminKey, session.serviceId, page, tick])

  const search = () => { applied.current = { text: text.trim(), tier }; if (page === 0) setTick(t => t + 1); else setPage(0) }

  return (
    <section className="page">
      <h2 className="section-label big">사용자 검색</h2>
      <form className="search-bar boxed" onSubmit={e => { e.preventDefault(); search() }}>
        <input value={text} onChange={e => setText(e.target.value)} placeholder="이름(ID) 입력 — 정확히 일치하는 값만 검색됩니다" />
        <select value={tier} onChange={e => setTier(e.target.value)} aria-label="등급 선택">
          <option value="">등급 선택</option>
          {tiers.map(t => <option key={t} value={t}>{t}</option>)}
        </select>
        <button className="btn" type="submit">조회</button>
      </form>

      <h2 className="section-label big">사용자 목록</h2>
      {loading && !data ? <p className="state-line"><LoaderCircle className="spin" size={18} /> 불러오는 중입니다.</p> : (
        <div className="table-card">
          <div className="table-scroll">
            <table>
              <thead><tr><th>이름 (ID)</th><th>등급</th><th>크레딧</th><th>이상 점수</th><th /></tr></thead>
              <tbody>
                {data?.content.map(user => (
                  <tr key={user.userId}>
                    <td><b>{user.externalUserId}</b><small className="mono">{user.userId}</small></td>
                    <td><span className="tier">{user.tier ?? '—'}</span></td>
                    <td>{number(user.creditScore)}</td>
                    <td>{score(user.anomalyScore)}</td>
                    <td className="right"><button className="btn outline small" onClick={() => onOpenUser(user.userId)}>상세 조회</button></td>
                  </tr>
                ))}
                {!data?.content.length && <tr><td colSpan={5} className="empty-line">조건에 맞는 사용자가 없습니다.</td></tr>}
              </tbody>
            </table>
          </div>
          <Pager data={data} page={page} onPage={setPage} />
        </div>
      )}
    </section>
  )
}
