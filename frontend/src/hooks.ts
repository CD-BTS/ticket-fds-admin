import { useCallback, useEffect, useRef, useState } from 'react'
import { explainError } from './utils'

export interface ApiState<T> {
  data: T | null
  error: string
  loading: boolean
  /** 마지막으로 응답을 받은 시각. 화면의 "기준 시각" 표시에 씁니다. */
  loadedAt: Date | null
  reload: () => void
}

/**
 * API 한 건의 로딩 · 오류 · 재시도 상태를 관리합니다.
 * 다시 불러오는 동안에는 이전 데이터를 유지하고, 실패하면 데이터를 비워 오류 상태와 빈 데이터를 구분합니다.
 * initial을 주면 첫 요청을 건너뜁니다(로그인 때 이미 받은 응답 재사용).
 */
export function useApi<T>(fetcher: () => Promise<T>, deps: unknown[], options: { initial?: T | null; skip?: boolean } = {}): ApiState<T> {
  const { initial = null, skip = false } = options
  const [state, setState] = useState<{ data: T | null; error: string; loading: boolean; loadedAt: Date | null }>({
    data: initial, error: '', loading: !skip && initial == null, loadedAt: initial == null ? null : new Date(),
  })
  const [tick, setTick] = useState(0)
  const seeded = useRef(initial != null)

  useEffect(() => {
    if (skip) return
    if (seeded.current) { seeded.current = false; return }
    let alive = true
    setState(s => ({ ...s, loading: true, error: '' }))
    fetcher()
      .then(data => alive && setState({ data, error: '', loading: false, loadedAt: new Date() }))
      .catch(e => alive && setState({ data: null, error: explainError(e), loading: false, loadedAt: null }))
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, skip])

  const reload = useCallback(() => setTick(t => t + 1), [])
  return { ...state, reload }
}
