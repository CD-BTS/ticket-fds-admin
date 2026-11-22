import { useEffect, useState } from 'react'
import { LoaderCircle } from 'lucide-react'
import { api } from '../api/client'
import type { ConcertDetail as Detail } from '../api/types'
import { dateTimeUtc, explainError, number } from '../utils'
import type { Session } from '../utils'

interface Props {
  session: Session
  concertId: string
  onBuyers: () => void
  onError: (message: string) => void
}

export default function ConcertDetail({ session, concertId, onBuyers, onError }: Props) {
  const [detail, setDetail] = useState<Detail | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setDetail(null)
    api.concert(session.adminKey, session.serviceId, concertId)
      .then(d => alive && setDetail(d))
      .catch(e => alive && onError(`공연 상세 조회 실패: ${explainError(e)}`))
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.adminKey, session.serviceId, concertId])

  if (loading) return <p className="state-line"><LoaderCircle className="spin" size={18} /> 공연 정보를 불러오는 중입니다.</p>
  if (!detail) return <p className="empty-line">공연 정보를 불러오지 못했습니다.</p>

  const stat = (label: string, value: number | undefined) => (
    <div className="stat-row" key={label}>
      <span>{label}</span>
      <b className={value == null ? 'na' : ''} title={value == null ? 'API 미제공' : undefined}>{value == null ? '—' : number(value)}<small>건</small></b>
    </div>
  )

  return (
    <section className="page concert-detail">
      <div className="concert-left">
        <h1>{detail.concert.name}</h1>
        <p className="concert-when">{dateTimeUtc(detail.concert.startsAt)}{detail.concert.venue ? ` · ${detail.concert.venue}` : ''}</p>
        {/* 10초 단위 시계열 API(GET /admin/concerts/{id}/trends)는 아직 없습니다. */}
        <div className="chart-box">
          <div className="chart-head"><b>시간대별 이상탐지</b><span>10초 단위</span></div>
          <div className="chart-empty">시계열 API 미제공</div>
        </div>
      </div>
      <div className="concert-right">
        {stat('예매 시도', detail.bookingAttemptCount)}
        {stat('예매 성공', detail.bookingSuccessCount)}
        {stat('예매 실패', detail.bookingFailureCount)}
        {stat('이상 탐지', detail.anomalyUserCount)}
        {stat('제재 적용', detail.sanctionedUserCount)}
        <div className="stat-foot">
          <span>예매자 목록</span>
          <button className="btn outline" onClick={onBuyers}>조회</button>
        </div>
      </div>
    </section>
  )
}
