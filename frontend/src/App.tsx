import { useCallback, useEffect, useRef, useState } from 'react'
import { BookOpen, ChevronRight, LayoutDashboard, LogOut, ShieldCheck, Ticket, Users } from 'lucide-react'
import { api, ApiError } from './api/client'
import type { Concert, DashboardSummary, PageResponse } from './api/types'
import { useApi } from './hooks'
import type { Session } from './utils'
import Landing from './pages/Landing'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import ConcertList from './pages/ConcertList'
import ConcertDetail from './pages/ConcertDetail'
import ConcertBuyers from './pages/ConcertBuyers'
import UserList from './pages/UserList'
import UserDetail from './pages/UserDetail'

type ConsolePage = 'dashboard' | 'concerts' | 'concert' | 'buyers' | 'users' | 'user'
type Page = 'intro' | 'login' | ConsolePage

export const Brand = ({ onClick, sub = 'Admin Console' }: { onClick?: () => void; sub?: string }) => (
  <button className="brand" onClick={onClick} aria-label="BTS">
    <span className="brand-mark"><ShieldCheck size={18} /></span>
    <span><b>BTS</b><small>{sub}</small></span>
  </button>
)

export default function App() {
  const [page, setPage] = useState<Page>('intro')
  const [session, setSession] = useState<Session | null>(null)
  const [busy, setBusy] = useState(false)
  const [loginMessage, setLoginMessage] = useState('')
  const [concertId, setConcertId] = useState('')
  const [concertName, setConcertName] = useState('')
  const [userId, setUserId] = useState('')
  const [userLabel, setUserLabel] = useState('')
  // 로그인 때 받은 요약 응답을 대시보드 첫 화면에 한 번만 재사용합니다.
  const seed = useRef<DashboardSummary | null>(null)

  const go = useCallback((next: Page) => {
    // 관리자 화면은 로그인한 뒤에만 열립니다.
    if (next !== 'intro' && next !== 'login' && !session) {
      setLoginMessage('')
      setPage('login')
    } else setPage(next)
    window.scrollTo({ top: 0 })
  }, [session])

  const login = async (serviceId: string, adminKey: string) => {
    setBusy(true)
    setLoginMessage('')
    try {
      seed.current = await api.summary(adminKey, serviceId)
      setSession({ serviceId, adminKey })
      setPage('dashboard')
      window.scrollTo({ top: 0 })
    } catch (e) {
      const status = e instanceof ApiError ? e.status : -1
      setLoginMessage(
        status === 0 ? '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
          : status === 401 ? '관리자 API Key가 올바르지 않습니다.'
          : status === 403 ? '이 API Key로는 해당 서비스에 접근할 수 없습니다. 서비스 ID를 확인해 주세요.'
          : status === 400 ? '서비스 ID 형식이 올바르지 않습니다.'
          : '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  const logout = () => {
    seed.current = null
    setSession(null); setConcertId(''); setConcertName(''); setUserId(''); setUserLabel(''); setLoginMessage('')
    setPage('intro')
    window.scrollTo({ top: 0 })
  }

  const openConcert = (id: string, name = '') => { setConcertId(id); setConcertName(name); go('concert') }
  const openUser = (id: string, label = '') => { setUserId(id); setUserLabel(label); go('user') }

  if (page === 'intro') {
    return <Landing loggedIn={!!session} onConsole={() => go(session ? 'dashboard' : 'login')} />
  }
  if (!session || page === 'login') {
    return (
      <div className="public">
        <header className="public-bar">
          <Brand onClick={() => go('intro')} sub="Bullyang Ticket Sniper" />
          <nav><button className="btn ghost" onClick={() => go('intro')}>BTS 소개</button></nav>
        </header>
        <main className="public-main"><Login busy={busy} message={loginMessage} onLogin={login} /></main>
      </div>
    )
  }
  return (
    <Console session={session} seed={seed} page={page as ConsolePage} go={go} logout={logout}
      concertId={concertId} concertName={concertName} setConcertName={setConcertName}
      userId={userId} userLabel={userLabel} setUserLabel={setUserLabel}
      openConcert={openConcert} openUser={openUser} />
  )
}

interface ConsoleProps {
  session: Session
  seed: React.MutableRefObject<DashboardSummary | null>
  page: ConsolePage
  go: (page: Page) => void
  logout: () => void
  concertId: string
  concertName: string
  setConcertName: (name: string) => void
  userId: string
  userLabel: string
  setUserLabel: (label: string) => void
  openConcert: (id: string, name?: string) => void
  openUser: (id: string, label?: string) => void
}

function Console(p: ConsoleProps) {
  const { session, page, go } = p
  const [initial] = useState(() => p.seed.current)
  useEffect(() => { p.seed.current = null }, [p.seed])

  // 서비스 요약과 공연 목록은 여러 화면이 함께 쓰므로 여기서 한 번만 불러옵니다.
  const summary = useApi<DashboardSummary>(() => api.summary(session.adminKey, session.serviceId),
    [session.adminKey, session.serviceId], { initial })
  const concerts = useApi<PageResponse<Concert>>(
    () => api.concerts(session.adminKey, session.serviceId, { page: 0, size: 100, sort: 'startsAt,desc' }),
    [session.adminKey, session.serviceId])

  const concertName = p.concertName || concerts.data?.content.find(c => c.concertId === p.concertId)?.name || p.concertId
  const section: 'dashboard' | 'concerts' | 'users' =
    page === 'dashboard' ? 'dashboard' : page === 'users' || page === 'user' ? 'users' : 'concerts'

  const crumbs: { label: string; to?: Page }[] =
    page === 'dashboard' ? [{ label: '대시보드' }]
      : page === 'concerts' ? [{ label: '공연 관리' }]
      : page === 'concert' ? [{ label: '공연 관리', to: 'concerts' }, { label: concertName }]
      : page === 'buyers' ? [{ label: '공연 관리', to: 'concerts' }, { label: concertName, to: 'concert' }, { label: '참여 사용자' }]
      : page === 'users' ? [{ label: '사용자 관리' }]
      : [{ label: '사용자 관리', to: 'users' }, { label: p.userLabel || '사용자 상세' }]

  const item = (key: typeof section, label: string, to: Page, icon: React.ReactNode) =>
    <button className={section === key ? 'active' : ''} onClick={() => go(to)} aria-current={section === key ? 'page' : undefined}>{icon}{label}</button>

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="sidebar-inner">
          <Brand onClick={() => go('dashboard')} />
          <nav className="side-nav" aria-label="관리자 메뉴">
            <div className="side-label">모니터링</div>
            {item('dashboard', '대시보드', 'dashboard', <LayoutDashboard size={16} />)}
            {item('concerts', '공연 관리', 'concerts', <Ticket size={16} />)}
            {item('users', '사용자 관리', 'users', <Users size={16} />)}
            <div className="side-label">안내</div>
            <button onClick={() => go('intro')}><BookOpen size={16} />BTS 소개</button>
          </nav>
          <div className="side-foot">
            <div className="side-service"><span>연동 서비스</span><b>{session.serviceId}</b></div>
            <button onClick={p.logout}><LogOut size={16} />로그아웃</button>
          </div>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <nav className="crumbs" aria-label="현재 위치">
            {crumbs.map((c, i) => (
              <span key={i} style={{ display: 'contents' }}>
                {i > 0 && <ChevronRight size={14} />}
                {c.to ? <button onClick={() => go(c.to!)}>{c.label}</button> : <b>{c.label}</b>}
              </span>
            ))}
          </nav>
          <div className="topbar-right">
            <span>연동 서비스</span>
            <span className="service-chip"><i />{session.serviceId}</span>
          </div>
        </header>

        <main className="content">
          {page === 'dashboard' && <Dashboard session={session} summary={summary} concerts={concerts}
            onOpenConcert={p.openConcert} onAllConcerts={() => go('concerts')} />}
          {page === 'concerts' && <ConcertList session={session} onOpen={p.openConcert} />}
          {page === 'concert' && <ConcertDetail key={p.concertId} session={session} concertId={p.concertId}
            onLoaded={p.setConcertName} onBack={() => go('concerts')} onBuyers={() => go('buyers')} onOpenUser={p.openUser} />}
          {page === 'buyers' && <ConcertBuyers key={p.concertId} session={session} concertId={p.concertId} concertName={concertName}
            onBack={() => go('concert')} onOpenUser={p.openUser} />}
          {page === 'users' && <UserList session={session} summary={summary} onOpenUser={p.openUser} />}
          {page === 'user' && <UserDetail key={p.userId} session={session} userId={p.userId} concerts={concerts.data?.content ?? []}
            onLoaded={p.setUserLabel} onBack={() => go('users')} onOpenConcert={p.openConcert} />}
        </main>
      </div>
    </div>
  )
}
