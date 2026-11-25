import { useMemo, useState } from 'react'
import { Bar, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '../api/client'
import type { ConcertTrend } from '../api/types'
import { ChartTip } from '../charts'
import { useApi } from '../hooks'
import { ANIM, COLOR, ErrorState, Panel, SkeletonBlock } from '../ui'
import { number, timeZoneLabel } from '../utils'
import type { Session } from '../utils'

interface Props {
  session: Session
  concertId: string
  refreshKey: number
}

/** 백엔드가 허용하는 집계 간격(초)과 한 번에 조회할 수 있는 최대 구간 수. */
const INTERVALS = [60, 300, 600, 3600]
const MAX_BUCKETS = 500
const HOUR = 3600

const PRESETS: { key: string; label: string; seconds: number; interval: number }[] = [
  { key: '1h', label: '1시간', seconds: HOUR, interval: 60 },
  { key: '6h', label: '6시간', seconds: 6 * HOUR, interval: 60 },
  { key: '24h', label: '24시간', seconds: 24 * HOUR, interval: 300 },
  { key: '7d', label: '7일', seconds: 7 * 24 * HOUR, interval: 3600 },
  { key: '20d', label: '20일', seconds: 20 * 24 * HOUR, interval: 3600 },
]

const intervalLabel = (seconds: number) => (seconds < HOUR ? `${seconds / 60}분` : `${seconds / HOUR}시간`)
const toLocalInput = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
const pad = (n: number) => String(n).padStart(2, '0')

/** 조회 구간이 몇 개의 버킷으로 나뉘는지 계산합니다(시작 시각은 간격 단위로 내림). */
function bucketCount(fromMs: number, toMs: number, interval: number) {
  const step = interval * 1000
  return Math.ceil((toMs - Math.floor(fromMs / step) * step) / step)
}

export default function ConcertTrendChart({ session, concertId, refreshKey }: Props) {
  const [preset, setPreset] = useState('24h')
  const [from, setFrom] = useState(() => toLocalInput(new Date(Date.now() - 24 * HOUR * 1000)))
  const [to, setTo] = useState(() => toLocalInput(new Date()))
  const [interval, setIntervalSeconds] = useState(300)
  // 직접 지정 범위는 "조회"를 눌렀을 때만 요청합니다.
  const [applied, setApplied] = useState<{ from: string; to: string; interval: number } | null>(null)

  const fromMs = from ? new Date(from).getTime() : NaN
  const toMs = to ? new Date(to).getTime() : NaN
  const rangeOk = Number.isFinite(fromMs) && Number.isFinite(toMs) && fromMs < toMs
  const allowed = rangeOk ? INTERVALS.filter(s => bucketCount(fromMs, toMs, s) <= MAX_BUCKETS) : []
  const customError = !rangeOk ? '시작 시각이 종료 시각보다 앞서야 합니다.'
    : !allowed.length ? `조회 기간이 너무 깁니다. 1시간 간격으로도 ${MAX_BUCKETS}개 구간을 넘으니 기간을 줄여 주세요.`
    : !allowed.includes(interval) ? `${intervalLabel(interval)} 간격은 구간이 ${number(bucketCount(fromMs, toMs, interval))}개로 나뉘어 조회할 수 없습니다.` : ''

  const trend = useApi<ConcertTrend>(() => {
    if (preset === 'custom' && applied) {
      return api.concertTrends(session.adminKey, session.serviceId, concertId,
        { from: new Date(applied.from).toISOString(), to: new Date(applied.to).toISOString(), intervalSeconds: applied.interval })
    }
    const chosen = PRESETS.find(x => x.key === preset) ?? PRESETS[2]
    const now = new Date()
    return api.concertTrends(session.adminKey, session.serviceId, concertId, {
      from: new Date(now.getTime() - chosen.seconds * 1000).toISOString(), to: now.toISOString(), intervalSeconds: chosen.interval,
    })
  }, [session.adminKey, session.serviceId, concertId, preset, applied, refreshKey], { skip: preset === 'custom' && !applied })

  // 직접 지정에서 아직 조회하지 않았다면 이전 범위의 결과를 보여주지 않습니다.
  const data = preset === 'custom' && !applied ? null : trend.data
  const step = data?.intervalSeconds ?? 300
  const longRange = data ? new Date(data.to).getTime() - new Date(data.from).getTime() > 24 * HOUR * 1000 : false
  const tick = (iso: string) => {
    const d = new Date(iso)
    const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`
    return step >= HOUR || longRange ? `${d.getMonth() + 1}/${d.getDate()} ${time}` : time
  }
  const range = (iso: unknown) => {
    const start = new Date(String(iso))
    const end = new Date(start.getTime() + step * 1000)
    const f = (d: Date) => `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    return `${f(start)} ~ ${f(end)}`
  }
  const totals = useMemo(() => (data?.buckets ?? []).reduce((s, b) => ({
    attempts: s.attempts + b.attemptCount, anomalies: s.anomalies + b.anomalyAssessmentCount, sanctions: s.sanctions + b.sanctionAppliedCount,
  }), { attempts: 0, anomalies: 0, sanctions: 0 }), [data])
  const empty = !!data && !totals.attempts && !totals.anomalies && !totals.sanctions

  return (
    <Panel title="시간대별 추이"
      hint="예매 시도는 왼쪽 축, AI 이상 탐지와 정책 적용은 오른쪽 축을 씁니다. AI 이상 탐지는 이 공연 참여자가 이상 행동으로 평가된 건수, 정책 적용은 티켓팅 서비스가 실제 적용했다고 보고한 건수입니다. 세 값은 세는 대상이 달라 서로 더하거나 비율로 비교할 수 없습니다."
      sub={data ? `${range(data.from).split(' ~ ')[0]} ~ ${range(data.to).split(' ~ ')[0]} · ${intervalLabel(data.intervalSeconds)} 단위 (${timeZoneLabel()})` : undefined}
      actions={(
        <div className="seg" role="group" aria-label="조회 범위">
          {PRESETS.map(x => <button key={x.key} className={preset === x.key ? 'active' : ''} onClick={() => setPreset(x.key)}>최근 {x.label}</button>)}
          <button className={preset === 'custom' ? 'active' : ''} onClick={() => setPreset('custom')}>직접 지정</button>
        </div>
      )}>
      {preset === 'custom' && (
        <div style={{ marginBottom: 14 }}>
          <div className="filters">
            <label>시작<input className="input" type="datetime-local" value={from} onChange={e => setFrom(e.target.value)} /></label>
            <label>종료<input className="input" type="datetime-local" value={to} onChange={e => setTo(e.target.value)} /></label>
            <label>집계 간격
              <select className="select" value={interval} onChange={e => setIntervalSeconds(Number(e.target.value))}>
                {INTERVALS.map(s => <option key={s} value={s} disabled={rangeOk && !allowed.includes(s)}>
                  {intervalLabel(s)}{rangeOk && !allowed.includes(s) ? ' (구간 초과)' : ''}</option>)}
              </select>
            </label>
            <button className="btn primary" disabled={!!customError} onClick={() => setApplied({ from, to, interval })}>조회</button>
          </div>
          <p className="note" style={{ marginTop: 8, color: customError ? COLOR.danger : undefined }} role={customError ? 'alert' : undefined}>
            {customError && `${customError} `}
            {rangeOk && allowed.length > 0 && `이 기간에 선택할 수 있는 간격: ${allowed.map(intervalLabel).join(' · ')}. `}
            한 번에 최대 {MAX_BUCKETS}개 구간까지 조회합니다.
          </p>
        </div>
      )}

      {trend.error ? <ErrorState message={trend.error} onRetry={trend.reload} />
        : preset === 'custom' && !applied ? <div className="chart-empty">기간과 집계 간격을 정한 뒤 조회를 눌러 주세요.</div>
        : !data ? <SkeletonBlock h={280} />
        : empty ? <div className="chart-empty">선택한 기간에 예매 시도 · AI 이상 탐지 · 정책 적용 기록이 없습니다.</div>
        : (
          <div style={{ opacity: trend.loading ? 0.6 : 1 }}>
            <ResponsiveContainer width="100%" height={280}>
              <ComposedChart data={data.buckets} margin={{ top: 8, right: 0, bottom: 0, left: 0 }}>
                <CartesianGrid stroke={COLOR.grid} vertical={false} />
                <XAxis dataKey="startsAt" tickFormatter={tick} tickLine={false} axisLine={{ stroke: COLOR.grid }} minTickGap={36} />
                <YAxis yAxisId="attempt" allowDecimals={false} tickLine={false} axisLine={false} width={44} domain={[0, 'auto']} tickFormatter={v => number(v)} />
                <YAxis yAxisId="event" orientation="right" allowDecimals={false} tickLine={false} axisLine={false} width={44} domain={[0, 'auto']} tickFormatter={v => number(v)} />
                <Tooltip cursor={{ fill: COLOR.hover }} content={<ChartTip unit="건" title={l => range(l)} />} />
                <Bar yAxisId="attempt" dataKey="attemptCount" name="예매 시도" fill={COLOR.inkFaint} {...ANIM} />
                <Line yAxisId="event" dataKey="anomalyAssessmentCount" name="AI 이상 탐지" stroke={COLOR.danger} strokeWidth={2} dot={false} {...ANIM} />
                {data.sanctionRecorded &&
                  <Line yAxisId="event" dataKey="sanctionAppliedCount" name="정책 적용" stroke={COLOR.ink} strokeWidth={2} strokeDasharray="5 3" dot={false} {...ANIM} />}
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        )}

      {data && !trend.error && (
        <>
          <div className="legend" style={{ marginTop: 12 }}>
            <span><i style={{ background: COLOR.inkFaint }} />예매 시도 <b className="num">{number(totals.attempts)}건</b></span>
            <span><i className="line" style={{ background: COLOR.danger }} />AI 이상 탐지 <b className="num">{number(totals.anomalies)}건</b></span>
            <span><i className="line" style={{ background: data.sanctionRecorded ? COLOR.ink : COLOR.inkFaint }} />정책 적용{' '}
              {data.sanctionRecorded ? <b className="num">{number(totals.sanctions)}건</b> : <b className="na">기록 없음</b>}</span>
          </div>
        </>
      )}
    </Panel>
  )
}
