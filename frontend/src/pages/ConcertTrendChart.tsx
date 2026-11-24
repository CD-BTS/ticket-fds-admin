import { useEffect, useState } from 'react'
import { LoaderCircle } from 'lucide-react'
import { Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '../api/client'
import type { ConcertTrend } from '../api/types'
import { explainError } from '../utils'
import type { Session } from '../utils'

interface Props {
  session: Session
  concertId: string
}

/** 백엔드가 허용하는 집계 간격(초). AI 평가가 기본 60초 주기라 60초가 최소입니다. */
const INTERVALS = [60, 300, 600, 3600]
const MAX_BUCKETS = 500
const HOUR = 3600

/** 조회 범위 프리셋: 범위(초)와 버킷이 500개를 넘지 않는 간격. */
const PRESETS: { key: string; label: string; seconds: number; interval: number }[] = [
  { key: '1h', label: '최근 1시간', seconds: HOUR, interval: 60 },
  { key: '6h', label: '최근 6시간', seconds: 6 * HOUR, interval: 60 },
  { key: '24h', label: '최근 24시간', seconds: 24 * HOUR, interval: 300 },
  { key: '7d', label: '최근 7일', seconds: 7 * 24 * HOUR, interval: 3600 },
  { key: '20d', label: '최근 20일', seconds: 20 * 24 * HOUR, interval: 3600 },
]

const intervalLabel = (seconds: number) =>
  seconds < HOUR ? `${seconds}초 단위${seconds === 60 ? '' : ` (${seconds / 60}분)`}` : `${seconds / HOUR}시간 단위`

// datetime-local 입력값(브라우저 시간대)을 UTC ISO 문자열로 바꿉니다.
const toUtc = (local: string) => (local ? new Date(local).toISOString() : '')
const toLocalInput = (date: Date) => {
  const shifted = new Date(date.getTime() - date.getTimezoneOffset() * 60000)
  return shifted.toISOString().slice(0, 16)
}

export default function ConcertTrendChart({ session, concertId }: Props) {
  const [preset, setPreset] = useState('24h')
  const [customFrom, setCustomFrom] = useState(() => toLocalInput(new Date(Date.now() - 24 * HOUR * 1000)))
  const [customTo, setCustomTo] = useState(() => toLocalInput(new Date()))
  const [customInterval, setCustomInterval] = useState(300)
  const [applied, setApplied] = useState(0) // 직접 지정 범위는 "조회"를 눌렀을 때만 다시 불러옵니다.
  const [trend, setTrend] = useState<ConcertTrend | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    let query: Record<string, string | number>
    if (preset === 'custom') {
      const from = toUtc(customFrom)
      const to = toUtc(customTo)
      if (!from || !to || new Date(from) >= new Date(to)) {
        setTrend(null); setLoading(false); setError('시작 시각이 종료 시각보다 앞서야 합니다.')
        return
      }
      const buckets = Math.ceil((new Date(to).getTime() - new Date(from).getTime()) / 1000 / customInterval)
      if (buckets > MAX_BUCKETS) {
        setTrend(null); setLoading(false)
        setError(`구간이 ${buckets}개로 나뉩니다. 최대 ${MAX_BUCKETS}개까지 조회할 수 있으니 범위를 줄이거나 간격을 늘려 주세요.`)
        return
      }
      query = { from, to, intervalSeconds: customInterval }
    } else {
      const chosen = PRESETS.find(p => p.key === preset) ?? PRESETS[2]
      const to = new Date()
      query = {
        from: new Date(to.getTime() - chosen.seconds * 1000).toISOString(),
        to: to.toISOString(),
        intervalSeconds: chosen.interval,
      }
    }
    setLoading(true)
    setError('')
    api.concertTrends(session.adminKey, session.serviceId, concertId, query)
      .then(t => alive && setTrend(t))
      .catch(e => { if (alive) { setTrend(null); setError(`추이 조회 실패: ${explainError(e)}`) } })
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.adminKey, session.serviceId, concertId, preset, applied])

  const interval = trend?.intervalSeconds ?? (preset === 'custom' ? customInterval : PRESETS.find(p => p.key === preset)?.interval ?? 60)
  const tick = (iso: string) => {
    const date = new Date(iso)
    const time = date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })
    return interval >= HOUR || (trend && new Date(trend.to).getTime() - new Date(trend.from).getTime() > 24 * HOUR * 1000)
      ? `${date.getMonth() + 1}/${date.getDate()} ${time}` : time
  }
  const rows = trend?.buckets ?? []
  const totals = rows.reduce((sum, b) => ({
    attempts: sum.attempts + b.attemptCount,
    anomalies: sum.anomalies + b.anomalyAssessmentCount,
    sanctions: sum.sanctions + b.sanctionAppliedCount,
  }), { attempts: 0, anomalies: 0, sanctions: 0 })
  const empty = !rows.some(b => b.attemptCount || b.anomalyAssessmentCount || b.sanctionAppliedCount)

  return (
    <div className="chart-box">
      <div className="chart-head"><b>시간대별 추이</b><span>{intervalLabel(interval)}</span></div>
      <div className="chart-controls">
        <select value={preset} onChange={e => setPreset(e.target.value)} aria-label="조회 범위">
          {PRESETS.map(p => <option key={p.key} value={p.key}>{p.label}</option>)}
          <option value="custom">직접 지정</option>
        </select>
        {preset === 'custom' && (
          <>
            <input type="datetime-local" value={customFrom} onChange={e => setCustomFrom(e.target.value)} aria-label="시작 시각" />
            <span>~</span>
            <input type="datetime-local" value={customTo} onChange={e => setCustomTo(e.target.value)} aria-label="종료 시각" />
            <select value={customInterval} onChange={e => setCustomInterval(Number(e.target.value))} aria-label="집계 간격">
              {INTERVALS.map(s => <option key={s} value={s}>{intervalLabel(s)}</option>)}
            </select>
            <button className="btn outline small" onClick={() => setApplied(n => n + 1)}>조회</button>
          </>
        )}
      </div>

      {loading ? <div className="chart-empty"><LoaderCircle className="spin" size={18} /></div>
        : error ? <div className="chart-empty danger">{error}</div>
        : empty ? <div className="chart-empty">선택한 구간에 예매 시도 · 이상 평가 · 제재 적용 기록이 없습니다.</div>
        : (
          <div className="chart-area">
            <ResponsiveContainer width="100%" height={230}>
              <ComposedChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: -18 }}>
                <CartesianGrid stroke="#eef0f4" vertical={false} />
                <XAxis dataKey="startsAt" tickFormatter={tick} tick={{ fontSize: 11 }} minTickGap={28} />
                <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                <Tooltip labelFormatter={label => tick(String(label))} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="attemptCount" name="예매 시도(건)" fill="#8fa6ea" isAnimationActive={false} />
                <Line dataKey="anomalyAssessmentCount" name="이상 평가(건)" stroke="#c0463d" strokeWidth={2} dot={false} isAnimationActive={false} />
                {trend?.sanctionRecorded &&
                  <Line dataKey="sanctionAppliedCount" name="제재 적용(건)" stroke="#9a783a" strokeWidth={2} dot={false} isAnimationActive={false} />}
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        )}
      {trend && !loading && !error && (
        <p className="chart-note">
          구간 합계: 예매 시도 {totals.attempts}건 · 이상 평가 {totals.anomalies}건 · 제재 적용{' '}
          {trend.sanctionRecorded ? `${totals.sanctions}건` : '기록 없음'}.
          {' '}이상 평가는 이 공연 참여자가 참여 이후 AI에서 이상(점수 0.5 이상)으로 평가된 건수입니다.
        </p>
      )}
    </div>
  )
}
