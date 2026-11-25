import type { ReactNode } from 'react'
import { Bar, BarChart, CartesianGrid, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ANIM, COLOR } from './ui'
import { number, percent } from './utils'

export interface Slice {
  name: string
  value: number
  color: string
  /** 마우스를 올렸을 때 보이는 보충 설명 */
  hint?: string
}

interface TipEntry { name?: string | number; value?: unknown; color?: string; payload?: Record<string, unknown> }
/** 모든 차트가 같은 모양의 툴팁을 씁니다. */
export function ChartTip({ active, payload, label, title, unit = '' }: {
  active?: boolean; payload?: TipEntry[]; label?: unknown; title?: (label: unknown, entries: TipEntry[]) => ReactNode; unit?: string
}) {
  if (!active || !payload?.length) return null
  return (
    <div className="chart-tip">
      <b>{title ? title(label, payload) : String(label ?? '')}</b>
      {payload.map((e, i) => (
        <div key={i}><i style={{ background: (e.payload?.color as string) ?? e.color }} />{e.name}
          <em>{typeof e.value === 'number' ? number(e.value) : String(e.value)}{unit}</em></div>
      ))}
    </div>
  )
}

/** 도넛 + 값 목록. 합계가 0이면 임의의 도형을 그리지 않고 빈 상태를 보여줍니다. */
export function Donut({ data, unit, centerLabel, empty }: { data: Slice[]; unit: string; centerLabel: string; empty: string }) {
  const total = data.reduce((s, d) => s + d.value, 0)
  if (!total) return <div className="chart-empty" style={{ height: 168 }}>{empty}</div>
  const shown = data.filter(d => d.value > 0)
  return (
    <div className="donut">
      <div className="donut-chart">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={shown} dataKey="value" nameKey="name" innerRadius={58} outerRadius={80} paddingAngle={shown.length > 1 ? 1.5 : 0}
              startAngle={90} endAngle={-270} stroke="none" {...ANIM}>
              {shown.map(d => <Cell key={d.name} fill={d.color} />)}
            </Pie>
            <Tooltip content={<ChartTip unit={unit} title={(_, e) => String(e[0]?.name ?? '')} />} />
          </PieChart>
        </ResponsiveContainer>
        <div className="donut-center"><b>{number(total)}</b><span>{centerLabel}</span></div>
      </div>
      <Distribution data={data} unit={unit} total={total} />
    </div>
  )
}

export function Distribution({ data, unit, total }: { data: Slice[]; unit: string; total: number }) {
  return (
    <div className="dist">
      {data.map(d => (
        <div className="dist-row" key={d.name} title={d.hint}>
          <span className="name"><i style={{ background: d.color }} />{d.name}</span>
          <span className="val">{number(d.value)}{unit}<small>{percent(d.value, total)}</small></span>
        </div>
      ))}
    </div>
  )
}

/** 가로 막대 목록. 막대 길이는 max(기본: 항목 최댓값) 대비 비율입니다. */
export function HBars({ data, unit, max, total }: { data: Slice[]; unit: string; max?: number; total?: number }) {
  const top = max ?? Math.max(1, ...data.map(d => d.value))
  return (
    <div className="hbars">
      {data.map(d => (
        <div key={d.name} title={d.hint}>
          <div className="hbar-top">
            <span>{d.name}</span>
            <b>{number(d.value)}{unit}{total != null && <small>{percent(d.value, total)}</small>}</b>
          </div>
          <div className="hbar-track"><i style={{ width: `${top ? (d.value / top) * 100 : 0}%`, background: d.color }} /></div>
        </div>
      ))}
    </div>
  )
}

/** 100% 누적 막대 + 범례 */
export function StackBar({ data, unit }: { data: Slice[]; unit: string }) {
  const total = data.reduce((s, d) => s + d.value, 0)
  return (
    <>
      <div className="stackbar" role="img" aria-label={data.map(d => `${d.name} ${d.value}${unit}`).join(', ')}>
        {total > 0 && data.map(d => <i key={d.name} title={`${d.name} ${number(d.value)}${unit}`} style={{ width: `${(d.value / total) * 100}%`, background: d.color }} />)}
      </div>
      <Distribution data={data} unit={unit} total={total} />
    </>
  )
}

/** 구간별 사용자 수 히스토그램. Y축은 0부터 시작합니다. */
export function Histogram({ data, color = COLOR.ink, unit, xLabel, height = 220 }: {
  data: { label: string; count: number; color?: string }[]; color?: string; unit: string; xLabel: string; height?: number
}) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 8, right: 8, bottom: 18, left: -8 }} barCategoryGap="14%">
        <CartesianGrid stroke={COLOR.grid} vertical={false} />
        <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: COLOR.grid }} interval={0}
          label={{ value: xLabel, position: 'insideBottom', offset: -12, fontSize: 11.5 }} />
        <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={48} tickFormatter={v => number(v)} />
        <Tooltip cursor={{ fill: COLOR.hover }} content={<ChartTip unit={unit} title={l => `${xLabel} ${l}`} />} />
        <Bar dataKey="count" name="사용자" fill={color} radius={[2, 2, 0, 0]} {...ANIM}>
          {data.map(d => <Cell key={d.label} fill={d.color ?? color} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}
