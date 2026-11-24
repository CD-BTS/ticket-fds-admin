import { useEffect, useState } from 'react'
import { LoaderCircle } from 'lucide-react'
import { api } from '../api/client'
import type { ConcertDetail as Detail } from '../api/types'
import { dateTimeUtc, explainError, number } from '../utils'
import type { Session } from '../utils'
import ConcertTrendChart from './ConcertTrendChart'

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

  // 값이 없으면(응답에 없는 필드) "—", 0이면 0으로 구분해 보여줍니다.
  const stat = (label: string, value: number | null | undefined, unit: string, hint?: string) => (
    <div className="stat-row" key={label} title={hint}>
      <span>{label}</span>
      <b className={value == null ? 'na' : ''}>{value == null ? '—' : number(value)}{value != null && <small>{unit}</small>}</b>
    </div>
  )
  const sanctions = detail.sanctions

  return (
    <section className="page concert-detail">
      <div className="concert-left">
        <h1>{detail.concert.name}</h1>
        <p className="concert-when">{dateTimeUtc(detail.concert.startsAt)}{detail.concert.venue ? ` · ${detail.concert.venue}` : ''}</p>
        <ConcertTrendChart session={session} concertId={concertId} />
      </div>
      <div className="concert-right">
        {stat('예매 시도', detail.bookingAttemptCount, '건', '시작이 확인된 고유 예매 시도 수. 성공 + 실패 + 미종료와 같습니다.')}
        {stat('예매 성공', detail.bookingSuccessCount, '건')}
        {stat('예매 실패', detail.bookingFailureCount, '건', '마지막 상태가 거절 · 실패 · 이탈 · 만료인 시도')}
        {stat('미종료', detail.bookingInProgressCount, '건', '아직 성공도 실패도 아닌 시도')}
        {stat('이상 탐지', detail.anomalyUserCount, '명',
          '이 공연에 예매를 시도한 사용자 중 최신 AI 이상 점수가 0.5 이상인 사용자 수. AI 평가는 사용자 단위입니다.')}
        {stat('예상 제재 대상', detail.expectedSanctionUserCount, '명',
          '현재 Credit과 이상 점수로 계산한 예상 대상입니다. 실제로 적용된 제재가 아닙니다.')}
        <div className="stat-row" title="티켓팅 서비스가 실제로 적용했다고 보고한 제재의 고유 사용자 수">
          <span>제재 적용</span>
          {sanctions?.recorded
            ? <b>{number(sanctions.appliedUserCount)}<small>명</small></b>
            : <b className="na stat-text">기록 없음</b>}
        </div>
        {sanctions && !sanctions.recorded &&
          <p className="stat-note">티켓팅 서비스가 정책 적용 기록을 아직 보내지 않았습니다. 제재가 없었다는 뜻이 아닙니다.</p>}
        {detail.unassessedUserCount > 0 &&
          <p className="stat-note">참여자 {number(detail.bookingAttemptUserCount)}명 중 {number(detail.unassessedUserCount)}명은 AI 평가 전이라 이상 탐지 수에 포함되지 않았습니다.</p>}
        <div className="stat-foot">
          <span>예매자 목록</span>
          <button className="btn outline" onClick={onBuyers}>조회</button>
        </div>
      </div>
    </section>
  )
}
