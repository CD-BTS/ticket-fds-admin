import { useState } from 'react'
import { CornerDownLeft, LoaderCircle } from 'lucide-react'

interface Props {
  busy: boolean
  message: string
  onLogin: (serviceId: string, adminKey: string) => void
}

export default function Login({ busy, message, onLogin }: Props) {
  const [serviceId, setServiceId] = useState('')
  const [adminKey, setAdminKey] = useState('')
  const submit = () => onLogin(serviceId, adminKey)
  return (
    <section className="login-wrap">
      <form className="login-card" onSubmit={e => { e.preventDefault(); submit() }}>
        <h1>로그인</h1>
        <label>서비스 ID
          <input value={serviceId} onChange={e => setServiceId(e.target.value)} placeholder="ticket-service-a" autoComplete="off" />
        </label>
        <label>관리자 API Key
          <input type="password" value={adminKey} onChange={e => setAdminKey(e.target.value)} placeholder="관리자 인증 키" autoComplete="off" />
        </label>
        {message && <p className="form-message">{message}</p>}
        <button className="enter-button" type="submit" disabled={busy} aria-label="로그인">
          {busy ? <LoaderCircle className="spin" size={20} /> : <CornerDownLeft size={20} />}
        </button>
      </form>
      <p className="login-hint">관리자 API Key는 화면 메모리에만 보관되며 새로고침하면 다시 입력해야 합니다.</p>
    </section>
  )
}
