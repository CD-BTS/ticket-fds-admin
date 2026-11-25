import { useEffect, useRef, useState } from 'react'
import { Activity, ArrowDown, ArrowRight, Bot, ChevronDown, Gauge, Repeat, ShieldCheck, SlidersHorizontal, Users } from 'lucide-react'
import shotConcert from '../assets/landing/console-concert.png'
import shotCredit from '../assets/landing/console-credit.png'
import shotPolicy from '../assets/landing/console-policy.png'
import './landing.css'

interface Props {
  loggedIn: boolean
  onConsole: () => void
}

const PROBLEMS = [
  { icon: <Bot size={20} />, title: '매크로 자동화 예매', text: '사람보다 빠른 자동화 프로그램이 예매 시작과 동시에 좌석을 가져갑니다.' },
  { icon: <Users size={20} />, title: '다중 계정의 기회 독점', text: '한 사람이 여러 계정으로 여러 사람 몫의 예매 기회를 차지합니다.' },
  { icon: <Repeat size={20} />, title: '반복되는 선점과 취소', text: '잡아 두고 취소하는 좌석 때문에 실제 관객의 자리가 사라집니다.' },
]

const STEPS = [
  { icon: <Activity size={20} />, title: '행동 데이터 수집', text: '예매 과정에서 일어나는 사용자의 행동을 티켓팅 서비스로부터 수집합니다.' },
  { icon: <Bot size={20} />, title: 'AI 이상 탐지', text: 'AI가 최근 행동 패턴을 분석해 평소와 다른 예매 행동을 찾아냅니다.' },
  { icon: <Gauge size={20} />, title: '사용자 신뢰도 산출', text: '관람 이력과 탐지 결과를 합쳐 사용자별 신뢰 점수인 Credit을 산출합니다.' },
  { icon: <SlidersHorizontal size={20} />, title: '위험도 기반 정책 적용', text: '신뢰도와 위험도에 따라 사용자마다 다른 예매 정책을 적용합니다.' },
]

/** 위험도가 높아지는 순서의 네 가지 정책 */
const LEVELS = [
  { title: '정상 예매', text: '추가 절차 없이 그대로 예매합니다.' },
  { title: '추가 인증', text: 'CAPTCHA로 자동화 여부를 확인합니다.' },
  { title: '대기열 후순위', text: '대기열의 뒤쪽 순서로 배치합니다.' },
  { title: '예매 제한', text: '해당 예매 요청을 제한합니다.' },
]

const SHOTS = [
  { src: shotConcert, title: '공연별 예매 및 이상 탐지 현황' },
  { src: shotCredit, title: '사용자별 Credit 및 AI 분석' },
  { src: shotPolicy, title: '정책 적용 결과 모니터링' },
]

const prefersStatic = () => window.matchMedia('(max-width: 900px), (prefers-reduced-motion: reduce)').matches

export default function Landing({ loggedIn, onConsole }: Props) {
  const root = useRef<HTMLDivElement>(null)
  const pipeline = useRef<HTMLElement>(null)
  const [isStatic, setStatic] = useState(prefersStatic)
  const [active, setActive] = useState(0)
  const [shot, setShot] = useState(0)
  const [scrolled, setScrolled] = useState(false)

  // 소개 페이지에서만 문서 배경과 부드러운 스크롤을 적용합니다.
  useEffect(() => {
    document.documentElement.classList.add('landing-active')
    return () => document.documentElement.classList.remove('landing-active')
  }, [])

  useEffect(() => {
    const mq = window.matchMedia('(max-width: 900px), (prefers-reduced-motion: reduce)')
    const on = () => setStatic(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])

  // 화면에 들어온 요소만 한 번 등장시킵니다.
  useEffect(() => {
    const targets = root.current?.querySelectorAll('.reveal') ?? []
    if (!('IntersectionObserver' in window)) { targets.forEach(el => el.classList.add('in')); return }
    const io = new IntersectionObserver(entries => entries.forEach(e => {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target) }
    }), { threshold: 0.15, rootMargin: '0px 0px -6% 0px' })
    targets.forEach(el => io.observe(el))
    return () => io.disconnect()
  }, [isStatic])

  // 스크롤 위치를 읽기만 합니다(스크롤을 가로채지 않음). 고정 구간의 진행도로 활성 단계를 정합니다.
  useEffect(() => {
    let frame = 0
    const update = () => {
      frame = 0
      setScrolled(window.scrollY > 24)
      const el = pipeline.current
      if (!el || isStatic) return
      const rect = el.getBoundingClientRect()
      const range = rect.height - window.innerHeight
      const progress = range > 0 ? Math.min(1, Math.max(0, -rect.top / range)) : 0
      setActive(Math.min(STEPS.length - 1, Math.floor(progress * STEPS.length)))
    }
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update) }
    update()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    return () => { window.removeEventListener('scroll', onScroll); window.removeEventListener('resize', onScroll); cancelAnimationFrame(frame) }
  }, [isStatic])

  const jump = (id: string) => document.getElementById(id)?.scrollIntoView({ block: 'start' })
  const consoleLabel = loggedIn ? '콘솔로 돌아가기' : '관리자 콘솔'

  return (
    <div className="landing" ref={root}>
      <header className={`l-nav${scrolled ? ' solid' : ''}`}>
        <button className="l-brand" onClick={() => window.scrollTo({ top: 0 })} aria-label="BTS 처음으로">
          <span className="l-mark"><ShieldCheck size={17} /></span><b>BTS</b>
        </button>
        <nav aria-label="소개 페이지">
          <button onClick={() => jump('problem')}>문제</button>
          <button onClick={() => jump('pipeline')}>동작 과정</button>
          <button onClick={() => jump('policy')}>정책</button>
          <button onClick={() => jump('console')}>관리자 콘솔</button>
        </nav>
        <button className="l-btn sm" onClick={onConsole}>{consoleLabel}<ArrowRight size={14} /></button>
      </header>

      {/* 1. Hero */}
      <section className="l-hero">
        <div className="l-aurora" aria-hidden><i /><i /><i /></div>
        <div className="l-hero-inner">
          <p className="l-eyebrow rise" style={{ animationDelay: '0ms' }}>Bullyang Ticket Sniper</p>
          <h1>
            <span className="rise" style={{ animationDelay: '120ms' }}>공정한 예매의 시작,</span>
            <span className="rise grad" style={{ animationDelay: '260ms' }}>더 지능적인 티켓팅.</span>
          </h1>
          <p className="l-lead rise" style={{ animationDelay: '420ms' }}>AI로 이상 예매를 탐지하고, 위험도에 맞춰 대응합니다.</p>
          <div className="l-actions rise" style={{ animationDelay: '560ms' }}>
            <button className="l-btn primary" onClick={() => jump('problem')}>BTS 알아보기<ArrowDown size={16} /></button>
            <button className="l-btn" onClick={onConsole}>{consoleLabel}<ArrowRight size={16} /></button>
          </div>
        </div>
        <button className="l-scroll" onClick={() => jump('problem')} aria-label="아래로 스크롤"><ChevronDown size={22} /></button>
      </section>

      {/* 2. 문제 제기 */}
      <section className="l-section" id="problem">
        <div className="l-wrap">
          <p className="l-kicker reveal">문제</p>
          <h2 className="reveal">예매 버튼을 누르기 전에,<br />이미 결과가 정해져 있다면.</h2>
          <ol className="l-problems">
            {PROBLEMS.map((p, i) => (
              <li key={p.title} className="reveal" style={{ transitionDelay: `${i * 120}ms` }}>
                <span className="l-num">0{i + 1}</span>
                <div><h3>{p.icon}{p.title}</h3><p>{p.text}</p></div>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* 3. 동작 과정 */}
      <section className={`l-pipeline${isStatic ? ' static' : ''}`} id="pipeline" ref={pipeline}>
        {isStatic ? (
          <div className="l-wrap">
            <p className="l-kicker reveal">동작 과정</p>
            <h2 className="reveal">행동을 읽고, 신뢰도로 바꾸고,<br />정책으로 대응합니다.</h2>
            <ol className="l-steps-list">
              {STEPS.map((s, i) => (
                <li key={s.title} className="reveal" style={{ transitionDelay: `${i * 80}ms` }}>
                  <span className="l-node">{s.icon}</span>
                  <div><small>0{i + 1}</small><h3>{s.title}</h3><p>{s.text}</p></div>
                </li>
              ))}
            </ol>
          </div>
        ) : (
          <div className="l-stage">
            <div className="l-wrap">
              <p className="l-kicker">동작 과정</p>
              <h2>행동을 읽고, 신뢰도로 바꾸고,<br />정책으로 대응합니다.</h2>

              <div className="l-flow" role="img" aria-label={`BTS 동작 과정: ${STEPS.map(s => s.title).join(', ')}`}>
                {STEPS.map((s, i) => (
                  <div key={s.title} className={`l-flow-cell${i <= active ? ' on' : ''}${i === active ? ' now' : ''}`}>
                    {i < STEPS.length - 1 && (
                      <span className={`l-seg${active > i ? ' on' : ''}${active === i + 1 ? ' flowing' : ''}`} aria-hidden><i /><b /><b /><b /></span>
                    )}
                    <span className="l-node">{s.icon}</span>
                    <span className="l-flow-name">{s.title}</span>
                  </div>
                ))}
              </div>

              <div className="l-step-text" aria-live="polite">
                <span className="l-step-count">0{active + 1} / 0{STEPS.length}</span>
                <div key={active} className="l-step-body"><h3>{STEPS[active].title}</h3><p>{STEPS[active].text}</p></div>
              </div>
            </div>
          </div>
        )}
      </section>

      {/* 4. 위험도 기반 정책 */}
      <section className="l-section" id="policy">
        <div className="l-wrap">
          <p className="l-kicker reveal">위험도 기반 정책</p>
          <h2 className="reveal">모두를 막지 않습니다.<br />위험한 만큼만 대응합니다.</h2>
          <div className="l-scale reveal" aria-hidden><span>위험도 낮음</span><i /><span>높음</span></div>
          <ol className="l-levels">
            {LEVELS.map((l, i) => (
              <li key={l.title} className={`reveal lv-${i}`} style={{ transitionDelay: `${i * 110}ms` }}>
                <span className="l-dot" aria-hidden />
                <h3>{l.title}</h3>
                <p>{l.text}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* 5. 관리자 콘솔 */}
      <section className="l-section l-console" id="console">
        <div className="l-wrap">
          <p className="l-kicker reveal">관리자 콘솔</p>
          <h2 className="reveal">무엇을 탐지했고, 어떻게 대응했는지.<br />한 화면에서 확인합니다.</h2>
          <div className="l-showcase">
            <div className="l-tabs reveal" role="tablist" aria-label="관리자 콘솔 화면">
              {SHOTS.map((s, i) => (
                <button key={s.title} role="tab" aria-selected={shot === i} className={shot === i ? 'on' : ''}
                  onClick={() => setShot(i)} onMouseEnter={() => setShot(i)} onFocus={() => setShot(i)}>{s.title}</button>
              ))}
            </div>
            <figure className="l-frame reveal zoom">
              <div className="l-frame-bar" aria-hidden><i /><i /><i /></div>
              <div className="l-frame-body">
                {SHOTS.map((s, i) => <img key={s.title} src={s.src} alt={`관리자 콘솔 화면: ${s.title}`} className={shot === i ? 'on' : ''} loading="lazy" />)}
              </div>
              <figcaption>테스트 데이터로 표시한 실제 콘솔 화면입니다.</figcaption>
            </figure>
          </div>
        </div>
      </section>

      {/* 6. CTA · Footer */}
      <section className="l-cta">
        <div className="l-aurora small" aria-hidden><i /><i /></div>
        <div className="l-wrap reveal">
          <h2>공정한 예매 환경을<br />지금 확인해 보세요.</h2>
          <button className="l-btn primary lg" onClick={onConsole}>{consoleLabel}<ArrowRight size={18} /></button>
        </div>
      </section>
      <footer className="l-footer">
        <span className="l-brand"><span className="l-mark"><ShieldCheck size={15} /></span><b>BTS</b></span>
        <span>AI 기반 부정 예매 탐지 및 대응</span>
      </footer>
    </div>
  )
}
