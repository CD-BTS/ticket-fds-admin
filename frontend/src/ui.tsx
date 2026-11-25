import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AlertTriangle, Check, ChevronLeft, ChevronRight, Copy, Inbox, Info, RefreshCw } from 'lucide-react'
import { dateTime, number } from './utils'

/* ── 색상 원칙: 일반 정보는 무채색, 주의 · 위험처럼 의미가 있는 상태에만 색을 씁니다 ── */
export type Tone = 'ok' | 'neutral' | 'warn' | 'danger' | 'muted'
export const COLOR = {
  primary: '#2563eb',
  /** 차트의 기본 계열(진한 무채색)과 보조 계열 */
  ink: '#334155', inkSoft: '#94a3b8', inkFaint: '#cbd5e1', track: '#e9eef4',
  ok: '#16a34a', warn: '#d97706', delay: '#b45309', danger: '#dc2626', muted: '#94a3b8', grid: '#e9eef4',
  /** 글자에 쓰는 강조색(배경 위 대비를 위해 한 단계 어두운 값)과 차트 hover 배경 */
  dangerText: '#b91c1c', warnText: '#b45309', hover: '#f4f6f9',
}
/** 차트 자체의 등장 애니메이션은 끕니다(값이 그려지다 만 상태로 보이지 않도록). 화면 전환은 탭 · 막대의 CSS 전환으로 처리합니다. */
export const ANIM = { isAnimationActive: false }

/** Credit 위험 등급. 화면에는 한글 명칭만 쓰고 원래 값은 도움말에 둡니다. 알 수 없는 값은 그대로 보여줍니다. */
const TIERS: Record<string, { label: string; tone: Tone; color: string }> = {
  NORMAL: { label: '정상', tone: 'ok', color: COLOR.inkFaint },
  SUSPICIOUS: { label: '의심', tone: 'warn', color: COLOR.warn },
  HIGH_RISK: { label: '위험', tone: 'danger', color: COLOR.danger },
  BASE: { label: '미산정', tone: 'muted', color: COLOR.track },
}
export const TIER_ORDER = ['NORMAL', 'SUSPICIOUS', 'HIGH_RISK']
const TIER_HINT: Record<string, string> = { BASE: 'Credit이 아직 한 번도 산정되지 않은 기본 상태' }
export const tierInfo = (tier: string | null | undefined) =>
  tier == null ? { label: '등급 없음', tone: 'muted' as Tone, color: COLOR.track }
    : TIERS[tier] ?? { label: tier, tone: 'muted' as Tone, color: COLOR.track }
/** 등급 경계(백엔드 Credit 정책: 40점 이상 정상, 20~39점 의심, 19점 이하 위험). 표시용 기준선입니다. */
export const TIER_BOUNDS = { normal: 40, suspicious: 20 }

const POLICIES: Record<string, { label: string; tone: Tone; color: string }> = {
  ALLOW: { label: '허용', tone: 'neutral', color: COLOR.inkFaint },
  REQUIRE_CAPTCHA: { label: 'CAPTCHA 인증', tone: 'warn', color: COLOR.warn },
  DELAY: { label: '대기열 후순위', tone: 'warn', color: COLOR.delay },
  RESTRICT: { label: '예매 제한', tone: 'danger', color: COLOR.danger },
}
export const POLICY_ORDER = ['ALLOW', 'REQUIRE_CAPTCHA', 'DELAY', 'RESTRICT']
export const policyInfo = (action: string | null | undefined) =>
  action == null ? { label: '—', tone: 'muted' as Tone, color: COLOR.track }
    : POLICIES[action] ?? { label: action, tone: 'muted' as Tone, color: COLOR.track }

const STATUSES: Record<string, { label: string; tone: Tone }> = {
  OK: { label: '평가 완료', tone: 'neutral' },
  INSUFFICIENT_DATA: { label: '데이터 부족', tone: 'warn' },
  INVALID: { label: '평가 불가', tone: 'danger' },
  NOT_EVALUATED: { label: '미평가', tone: 'muted' },
}
export const statusInfo = (status: string | null | undefined) =>
  status == null ? { label: '—', tone: 'muted' as Tone } : STATUSES[status] ?? { label: status, tone: 'muted' as Tone }

/** 작은 색 점 + 텍스트. 색만으로 상태를 구분하지 않습니다. */
export const Badge = ({ tone = 'muted', children, title }: { tone?: Tone; children: ReactNode; title?: string }) =>
  <span className={`badge ${tone}`} title={title}>{children}</span>
export const TierBadge = ({ tier }: { tier: string | null | undefined }) => {
  const t = tierInfo(tier)
  return <Badge tone={t.tone} title={tier ? TIER_HINT[tier] : undefined}>{t.label}</Badge>
}
export const PolicyBadge = ({ action }: { action: string | null | undefined }) => {
  const p = policyInfo(action)
  return <Badge tone={p.tone}>{p.label}</Badge>
}
export const StatusBadge = ({ status }: { status: string | null | undefined }) => {
  const s = statusInfo(status)
  return <Badge tone={s.tone}>{s.label}</Badge>
}

/** 집계 기준 · 정의처럼 자세한 설명은 화면에 늘어놓지 않고 도움말로 둡니다. */
export function Hint({ text }: { text: string }) {
  const icon = useRef<HTMLSpanElement>(null)
  const tip = useRef<HTMLSpanElement>(null)
  const [open, setOpen] = useState(false)

  // 아이콘 위치를 기준으로 화면 안쪽에 놓습니다. 오른쪽 · 아래쪽이 모자라면 안쪽으로 당기거나 위로 올립니다.
  const place = () => {
    const anchor = icon.current
    const el = tip.current
    if (!anchor || !el) return
    const a = anchor.getBoundingClientRect()
    const margin = 8
    const left = Math.max(margin, Math.min(a.left - margin, window.innerWidth - el.offsetWidth - margin))
    const below = a.bottom + margin
    const top = below + el.offsetHeight > window.innerHeight - margin ? Math.max(margin, a.top - el.offsetHeight - margin) : below
    el.style.left = `${left}px`
    el.style.top = `${top}px`
    el.style.visibility = 'visible'
  }
  useLayoutEffect(() => { if (open) place() }, [open])

  // 고정 위치라서 스크롤하거나 창 크기가 바뀌면 아이콘을 따라 다시 놓습니다.
  useEffect(() => {
    if (!open) return
    window.addEventListener('scroll', place, true)
    window.addEventListener('resize', place)
    return () => { window.removeEventListener('scroll', place, true); window.removeEventListener('resize', place) }
  }, [open])

  // 마우스는 올리면, 키보드는 포커스하면 열립니다. 터치는 누를 때마다 열고 닫으며, 다른 곳을 누르면(포커스를 잃으면) 닫힙니다.
  // 터치에서는 누른 직후 브라우저가 마우스 이탈 이벤트를 만들어 내므로, 올림 · 이탈은 실제 마우스일 때만 반영합니다.
  const touch = useRef(false)
  return (
    <span ref={icon} className="hint" tabIndex={0} role="button" aria-label={`도움말: ${text}`} aria-expanded={open}
      onPointerDown={e => { touch.current = e.pointerType !== 'mouse' }}
      onPointerEnter={e => { if (e.pointerType === 'mouse') setOpen(true) }}
      onPointerLeave={e => { if (e.pointerType === 'mouse') setOpen(false) }}
      onFocus={() => { if (!touch.current) setOpen(true) }} onBlur={() => setOpen(false)}
      onClick={e => { e.preventDefault(); e.stopPropagation(); setOpen(v => (touch.current ? !v : true)) }}
      onKeyDown={e => { if (e.key === 'Escape') setOpen(false) }}>
      <Info size={13} />
      {open && createPortal(<span ref={tip} className="tip-pop" role="tooltip">{text}</span>, document.body)}
    </span>
  )
}

/** Credit 숫자와 0~100 가로 막대. 정상 구간은 무채색, 의심 · 위험 등급만 색으로 표시합니다. */
export function CreditBar({ value, tier }: { value: number | null | undefined; tier?: string | null }) {
  if (value == null) return <span className="na">—</span>
  const tone = tierInfo(tier).tone
  const fill = tone === 'danger' ? COLOR.danger : tone === 'warn' ? COLOR.warn : COLOR.inkSoft
  return (
    <span className="credit">
      <b>{value}</b>
      <span className="credit-track" aria-hidden><i style={{ width: `${Math.max(0, Math.min(100, value))}%`, background: fill }} /></span>
    </span>
  )
}

/* ── 레이아웃 ── */
export function PageHead({ title, sub, children }: { title: ReactNode; sub?: ReactNode; children?: ReactNode }) {
  return (
    <div className="page-head">
      <div><h1>{title}</h1>{sub && <p>{sub}</p>}</div>
      {children && <div className="page-actions">{children}</div>}
    </div>
  )
}

export function Panel({ title, hint, sub, actions, children, flush, foot }: {
  title?: ReactNode; hint?: string; sub?: ReactNode; actions?: ReactNode; children: ReactNode; flush?: boolean; foot?: ReactNode
}) {
  return (
    <section className="panel">
      {(title || actions) && (
        <div className="panel-head" style={flush ? { paddingBottom: 14 } : undefined}>
          <div style={{ minWidth: 0 }}>
            {typeof title === 'string' ? <h2>{title}{hint && <Hint text={hint} />}</h2> : title}
            {sub && <p>{sub}</p>}
          </div>
          {actions}
        </div>
      )}
      <div className={`panel-body${flush ? ' flush' : ''}`}>{children}</div>
      {foot && <div className="panel-foot">{foot}</div>}
    </section>
  )
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: [T, string, ReactNode?][]; value: T; onChange: (tab: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map(([key, label, count]) => (
        <button key={key} role="tab" aria-selected={value === key} className={value === key ? 'active' : ''} onClick={() => onChange(key)}>
          {label}{count != null && <small>{count}</small>}</button>
      ))}
    </div>
  )
}

export interface KpiItem {
  label: string
  value: ReactNode
  unit?: string
  sub?: ReactNode
  /** 숫자가 아닌 상태 문구(예: 기록 없음) */
  text?: boolean
  na?: boolean
  hint?: string
  gap?: boolean
}
export function Kpis({ items, loading, compact }: { items: KpiItem[]; loading?: boolean; compact?: boolean }) {
  return (
    <div className={`kpis${compact ? ' compact' : ''}`}>
      {items.map(k => (
        <div key={k.label} className={`kpi${k.gap ? ' kpi-group-gap' : ''}`}>
          <div className="kpi-label">{k.label}{k.hint && <Hint text={k.hint} />}</div>
          {loading ? <Skeleton w={84} h={compact ? 28 : 36} style={{ marginTop: 8 }} /> : (
            <div className={`kpi-value${k.text ? ' text' : ''}${k.na ? ' na' : ''}`}>{k.value}{k.unit && !k.na && <small>{k.unit}</small>}</div>
          )}
          {k.sub && !loading && <div className="kpi-sub">{k.sub}</div>}
        </div>
      ))}
    </div>
  )
}

/* ── 로딩 · 빈 데이터 · 오류 ── */
export const Skeleton = ({ w = '100%', h = 14, style }: { w?: number | string; h?: number; style?: React.CSSProperties }) =>
  <span className="skeleton" style={{ width: w, height: h, ...style }} />

export const SkeletonRows = ({ rows = 5, cols = 5 }: { rows?: number; cols?: number }) => (
  <table className="tbl"><tbody>{Array.from({ length: rows }, (_, r) => (
    <tr key={r}>{Array.from({ length: cols }, (_, c) => <td key={c}><Skeleton w={c === 0 ? '70%' : '50%'} /></td>)}</tr>
  ))}</tbody></table>
)
export const SkeletonBlock = ({ h = 220 }: { h?: number }) => <Skeleton h={h} style={{ borderRadius: 6 }} />

/** 정상 응답인데 데이터가 없는 상태 */
export const Empty = ({ title, children }: { title: string; children?: ReactNode }) => (
  <div className="state"><Inbox size={22} /><b>{title}</b>{children && <span>{children}</span>}</div>
)
/** API 호출이 실패한 상태. 빈 데이터와 구분하고 재시도 동선을 줍니다. */
export const ErrorState = ({ message, onRetry, inline }: { message: string; onRetry?: () => void; inline?: boolean }) => inline ? (
  <div className="inline-error" role="alert"><AlertTriangle size={15} /><span>{message}</span>
    {onRetry && <button className="btn sm" onClick={onRetry}><RefreshCw size={13} />다시 시도</button>}</div>
) : (
  <div className="state error" role="alert"><AlertTriangle size={22} /><b>데이터를 불러오지 못했습니다</b><span>{message}</span>
    {onRetry && <button className="btn sm" onClick={onRetry}><RefreshCw size={13} />다시 시도</button>}</div>
)

export function RefreshButton({ onClick, loading, loadedAt }: { onClick: () => void; loading?: boolean; loadedAt?: Date | null }) {
  return (
    <>
      {loadedAt && <span className="note num">{dateTime(loadedAt.toISOString())} 기준</span>}
      <button className="btn" onClick={onClick} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} />새로고침</button>
    </>
  )
}

export function Pager({ page, totalPages, totalElements, hasNext, onPage, unit = '건' }: {
  page: number; totalPages: number; totalElements: number; hasNext: boolean; onPage: (p: number) => void; unit?: string
}) {
  return (
    <>
      <span className="num">전체 {number(totalElements)}{unit} · {page + 1} / {Math.max(1, totalPages)} 페이지</span>
      <div className="pager">
        <button className="btn sm" disabled={page <= 0} onClick={() => onPage(page - 1)}><ChevronLeft size={14} />이전</button>
        <button className="btn sm" disabled={!hasNext} onClick={() => onPage(page + 1)}>다음<ChevronRight size={14} /></button>
      </div>
    </>
  )
}

/** 최근 항목만 먼저 보여주고 "더보기"로 이미 받아 온 나머지를 펼칩니다. */
export function useMore<T>(items: T[], step: number) {
  const [count, setCount] = useState(step)
  const rest = items.length - count
  return {
    shown: items.slice(0, count),
    more: rest > 0 ? <button className="more-btn" onClick={() => setCount(c => c + step)}>더보기 ({number(Math.min(step, rest))}건)</button> : null,
  }
}

export function CopyButton({ value, label = '복사' }: { value: string; label?: string }) {
  const [done, setDone] = useState(false)
  const copy = () => {
    navigator.clipboard?.writeText(value).then(() => { setDone(true); setTimeout(() => setDone(false), 1500) }).catch(() => undefined)
  }
  return <button className="copy" onClick={copy} aria-label={`${label}: ${value}`}>{done ? <Check size={12} /> : <Copy size={12} />}{done ? '복사됨' : label}</button>
}
