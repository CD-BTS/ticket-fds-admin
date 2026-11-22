import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, X } from 'lucide-react'
import { api } from './api/client'
import type { Concert } from './api/types'
import { explainError } from './utils'
import type { Session } from './utils'
import Intro from './pages/Intro'
import Login from './pages/Login'
import ConcertList from './pages/ConcertList'
import ConcertDetail from './pages/ConcertDetail'
import ConcertBuyers from './pages/ConcertBuyers'
import UserList from './pages/UserList'
import UserDetail from './pages/UserDetail'

type Page = 'intro' | 'login' | 'concerts' | 'concert' | 'buyers' | 'users' | 'user'
const NEEDS_LOGIN: Page[] = ['concerts', 'concert', 'buyers', 'users', 'user']

export default function App() {
  const [page, setPage] = useState<Page>('intro')
  const [session, setSession] = useState<Session | null>(null)
  const [busy, setBusy] = useState(false)
  const [loginMessage, setLoginMessage] = useState('')
  const [error, setError] = useState('')
  const [tiers, setTiers] = useState<string[]>([])
  const [concerts, setConcerts] = useState<Concert[]>([])
  const [concertId, setConcertId] = useState('')
  const [userId, setUserId] = useState('')
  // 화면 전환은 상태로만 처리합니다. 페이지를 옮길 때 이전 오류 배너는 지웁니다.
  const [after, setAfter] = useState<Page>('concerts')

  const go = useCallback((next: Page) => {
    setError('')
    if (NEEDS_LOGIN.includes(next) && !session) {
      setAfter(next === 'concert' || next === 'buyers' || next === 'user' ? 'concerts' : next)
      setLoginMessage('로그인이 필요한 메뉴입니다. 서비스 ID와 관리자 API Key를 입력해 주세요.')
      setPage('login')
      return
    }
    setPage(next)
    window.scrollTo({ top: 0 })
  }, [session])

  const login = async (rawServiceId: string, adminKey: string) => {
    const serviceId = rawServiceId.trim()
    if (!serviceId || !adminKey.trim()) { setLoginMessage('서비스 ID와 관리자 API Key를 입력해 주세요.'); return }
    setBusy(true)
    setLoginMessage('')
    try {
      const summary = await api.summary(adminKey, serviceId)
      setTiers(Object.keys(summary.usersByTier ?? {}))
      setSession({ serviceId, adminKey })
      setPage(after)
    } catch (e) {
      setLoginMessage(`로그인 실패: ${explainError(e)} 관리자 키(BTS_ADMIN_API_KEYS에 등록된 키)와 서비스 ID를 확인해 주세요.`)
    } finally {
      setBusy(false)
    }
  }

  const logout = () => {
    setSession(null); setConcerts([]); setTiers([]); setConcertId(''); setUserId('')
    setLoginMessage(''); setError(''); setAfter('concerts'); setPage('intro')
  }

  // 사용자 예매 목록에서 공연 이름을 보여 주려고 로그인 직후 공연 목록을 미리 불러옵니다.
  useEffect(() => {
    if (!session) return
    api.concerts(session.adminKey, session.serviceId, { page: 0, size: 100, sort: 'startsAt,desc' })
      .then(p => setConcerts(p.content)).catch(() => undefined)
  }, [session])

  const concertName = concerts.find(c => c.concertId === concertId)?.name ?? concertId
  const active = (...pages: Page[]) => (pages.includes(page) ? 'active' : '')

  return (
    <div className="app">
      <header className="nav">
        <button className="nav-logo" onClick={() => go('intro')} aria-label="소개 페이지로">
          {/* 로고 확정 전: 자리표시입니다. 확정되면 <img>로 교체하세요. */}
          <span className="nav-logo-box">로고</span>
        </button>
        <nav>
          <button className={active('concerts', 'concert', 'buyers')} onClick={() => go('concerts')}>공연 목록</button>
          <button className={active('users', 'user')} onClick={() => go('users')}>사용자 목록</button>
          <button className={active('intro')} onClick={() => go('intro')}>소개</button>
          {session
            ? <span className="nav-user"><b>{session.serviceId}</b>님<button className="nav-logout" onClick={logout}>로그아웃</button></span>
            : <button className={active('login')} onClick={() => { setLoginMessage(''); setAfter('concerts'); setPage('login') }}>로그인</button>}
        </nav>
      </header>

      <main>
        {error && <div className="error-banner"><AlertTriangle size={16} /><span>{error}</span>
          <button onClick={() => setError('')} aria-label="오류 닫기"><X size={15} /></button></div>}

        {page === 'intro' && <Intro />}
        {page === 'login' && <Login busy={busy} message={loginMessage} onLogin={login} />}
        {session && page === 'concerts' && <ConcertList session={session} onLoaded={setConcerts} onError={setError}
          onOpen={id => { setConcertId(id); go('concert') }} />}
        {session && page === 'concert' && <ConcertDetail session={session} concertId={concertId} onError={setError}
          onBuyers={() => go('buyers')} />}
        {session && page === 'buyers' && <ConcertBuyers session={session} concertId={concertId} concertName={concertName}
          onError={setError} onOpenUser={id => { setUserId(id); go('user') }} />}
        {session && page === 'users' && <UserList session={session} tiers={tiers} onError={setError}
          onOpenUser={id => { setUserId(id); go('user') }} />}
        {session && page === 'user' && <UserDetail session={session} userId={userId} concerts={concerts} onError={setError}
          onOpenConcert={id => { setConcertId(id); go('concert') }} />}
      </main>
    </div>
  )
}
