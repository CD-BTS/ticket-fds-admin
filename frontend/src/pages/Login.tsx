import { useState } from 'react'
import { AlertTriangle, Eye, EyeOff, LoaderCircle } from 'lucide-react'
import { Hint } from '../ui'

interface Props {
  busy: boolean
  message: string
  onLogin: (serviceId: string, adminKey: string) => void
}

export default function Login({ busy, message, onLogin }: Props) {
  const [serviceId, setServiceId] = useState('')
  const [adminKey, setAdminKey] = useState('')
  const [show, setShow] = useState(false)
  const [touched, setTouched] = useState(false)

  const serviceError = !serviceId.trim() ? '서비스 ID를 입력해 주세요.' : /\s/.test(serviceId.trim()) ? '서비스 ID에는 공백을 넣을 수 없습니다.' : ''
  const keyError = !adminKey.trim() ? 'API Key를 입력해 주세요.' : ''
  const submit = () => {
    setTouched(true)
    if (!serviceError && !keyError) onLogin(serviceId.trim(), adminKey.trim())
  }

  return (
    <section className="login">
      <form className="login-card" onSubmit={e => { e.preventDefault(); submit() }} noValidate>
        <h1>관리자 로그인</h1>

        <label className="field">서비스 ID
          <input className={`input${touched && serviceError ? ' invalid' : ''}`} value={serviceId} onChange={e => setServiceId(e.target.value)}
            autoComplete="off" autoFocus aria-invalid={touched && !!serviceError} />
          {touched && serviceError && <em>{serviceError}</em>}
        </label>
        <div className="field">
          <span><label htmlFor="admin-key">API Key</label>
            <Hint text="보안을 위해 API Key는 저장되지 않습니다. 페이지를 새로고침하면 다시 입력해야 합니다." /></span>
          <span className="pw">
            <input id="admin-key" className={`input${touched && keyError ? ' invalid' : ''}`} type={show ? 'text' : 'password'} value={adminKey}
              onChange={e => setAdminKey(e.target.value)} autoComplete="off" aria-invalid={touched && !!keyError} />
            <button type="button" onClick={() => setShow(v => !v)} aria-label={show ? 'API Key 숨기기' : 'API Key 표시'}>
              {show ? <EyeOff size={16} /> : <Eye size={16} />}</button>
          </span>
          {touched && keyError && <em>{keyError}</em>}
        </div>

        {message && <div className="inline-error" role="alert" style={{ marginBottom: 12 }}><AlertTriangle size={15} /><span>{message}</span></div>}
        <button className="btn primary" type="submit" disabled={busy}>
          {busy ? <><LoaderCircle className="spin" size={16} />로그인 중</> : '로그인'}
        </button>
      </form>
    </section>
  )
}
