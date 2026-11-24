import { useEffect, useState } from 'react'
import { LoaderCircle, Search } from 'lucide-react'
import { api } from '../api/client'
import type { Concert } from '../api/types'
import { dateTimeUtc, explainError, number } from '../utils'
import type { Session } from '../utils'

interface Props {
  session: Session
  onOpen: (concertId: string) => void
  onError: (message: string) => void
  onLoaded: (concerts: Concert[]) => void
}

const SIZE = 100

export default function ConcertList({ session, onOpen, onError, onLoaded }: Props) {
  const [rows, setRows] = useState<Concert[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')

  useEffect(() => {
    let alive = true
    setLoading(true)
    api.concerts(session.adminKey, session.serviceId, { page: 0, size: SIZE, sort: 'startsAt,desc' })
      .then(page => { if (alive) { setRows(page.content); setTotal(page.totalElements); onLoaded(page.content) } })
      .catch(e => alive && onError(`공연 목록 조회 실패: ${explainError(e)}`))
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.adminKey, session.serviceId])

  const q = query.trim().toLowerCase()
  const shown = rows.filter(c => !q || c.name.toLowerCase().includes(q) || c.concertId.toLowerCase().includes(q))

  return (
    <section className="page narrow">
      <h2 className="section-label">공연 검색</h2>
      <form className="search-bar" onSubmit={e => { e.preventDefault(); setQuery(input) }}>
        <input value={input} onChange={e => setInput(e.target.value)} placeholder="이름 입력" />
        <button className="btn" type="submit"><Search size={15} />검색</button>
      </form>

      {loading ? <p className="state-line"><LoaderCircle className="spin" size={18} /> 공연 목록을 불러오는 중입니다.</p> : (
        <>
          <p className="muted-line">{shown.length}건{total > SIZE && ` · 최근 ${SIZE}건까지만 불러왔습니다 (전체 ${total}건)`}</p>
          <ul className="card-list scroll">
            {shown.map(c => (
              <li key={c.concertId} className="concert-card">
                <div className="concert-info">
                  <strong>{c.name}</strong>
                  <span>{dateTimeUtc(c.startsAt)}{c.venue ? ` · ${c.venue}` : ''}</span>
                </div>
                {/* 그 공연에 예매를 시도한 사용자 중 최신 AI 이상 점수가 0.5 이상인 고유 사용자 수(건수가 아님). */}
                <div className="concert-anomaly" title="이 공연에 예매를 시도한 사용자 중 AI 이상탐지 사용자 수">
                  이상탐지 <b className={c.anomalyUserCount == null ? '' : c.anomalyUserCount > 0 ? 'found' : 'zero'}>
                    {c.anomalyUserCount == null ? '—' : number(c.anomalyUserCount)}</b>명</div>
                <button className="btn outline" onClick={() => onOpen(c.concertId)}>상세 조회</button>
              </li>
            ))}
            {!shown.length && <li className="empty-line">조건에 맞는 공연이 없습니다.</li>}
          </ul>
        </>
      )}
    </section>
  )
}
