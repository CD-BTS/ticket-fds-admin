import { useState } from 'react'
import { Search } from 'lucide-react'
import { api } from '../api/client'
import type { Concert, PageResponse } from '../api/types'
import { useApi } from '../hooks'
import { Badge, COLOR, Empty, ErrorState, PageHead, Pager, Panel, RefreshButton, SkeletonRows } from '../ui'
import { dateTime, number } from '../utils'
import type { Session } from '../utils'

interface Props {
  session: Session
  onOpen: (concertId: string, name: string) => void
}

const SIZE = 20
const SORTS: [string, string][] = [['startsAt,desc', '공연 일시 늦은 순'], ['startsAt,asc', '공연 일시 빠른 순'], ['name,asc', '공연명 순']]

export default function ConcertList({ session, onOpen }: Props) {
  const [input, setInput] = useState('')
  const [name, setName] = useState('')
  const [sort, setSort] = useState(SORTS[0][0])
  const [page, setPage] = useState(0)

  // 이름 검색 · 정렬 · 페이지는 서버에서 처리합니다.
  const list = useApi<PageResponse<Concert>>(
    () => api.concerts(session.adminKey, session.serviceId, { page, size: SIZE, sort, name: name || undefined }),
    [session.adminKey, session.serviceId, page, sort, name])
  const data = list.data
  const now = Date.now()

  return (
    <div className="stack">
      <PageHead title="공연 관리">
        <RefreshButton onClick={list.reload} loading={list.loading} loadedAt={list.loadedAt} />
      </PageHead>

      <Panel flush
        title={(
          <form className="filters" onSubmit={e => { e.preventDefault(); setName(input.trim()); setPage(0) }}>
            <span className="input-icon" style={{ minWidth: 280 }}>
              <Search size={15} />
              <input className="input" value={input} onChange={e => setInput(e.target.value)} placeholder="공연명 검색" aria-label="공연명 검색" />
            </span>
            <select className="select" aria-label="정렬" value={sort} onChange={e => { setSort(e.target.value); setPage(0) }}>
              {SORTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
            <button className="btn primary" type="submit">검색</button>
            {name && <button className="btn ghost" type="button" onClick={() => { setInput(''); setName(''); setPage(0) }}>초기화</button>}
          </form>
        )}
        actions={data ? <span className="note num" style={{ whiteSpace: 'nowrap', lineHeight: '34px' }}>{name ? `검색 결과 ${number(data.totalElements)}개` : `공연 ${number(data.totalElements)}개`}</span> : undefined}
        foot={data && data.totalElements > 0
          ? <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} hasNext={data.hasNext} onPage={setPage} unit="개" />
          : undefined}>
        {list.error ? <ErrorState message={list.error} onRetry={list.reload} />
          : !data ? <SkeletonRows rows={5} cols={5} />
          : !data.content.length ? (
            <Empty title={name ? '검색 결과가 없습니다' : '연동된 공연이 없습니다'}>
              {name ? `"${name}"이(가) 포함된 공연이 없습니다.` : '티켓팅 서비스가 공연을 등록하면 여기에 표시됩니다.'}</Empty>
          ) : (
            <div className="tbl-wrap" style={{ opacity: list.loading ? 0.6 : 1 }}>
              <table className="tbl">
                <thead><tr>
                  <th>공연명</th><th>공연 일시</th><th>장소</th><th>상태</th>
                  <th className="num" title="이 공연에 예매를 시도한 사용자 중 이상 행동으로 판정된 사용자 수입니다. 예매 건수와 정책 적용 현황은 상세에서 확인합니다.">이상 탐지 사용자</th>
                  <th className="act" />
                </tr></thead>
                <tbody>{data.content.map(c => {
                  const ended = new Date(c.endsAt).getTime() < now
                  const upcoming = new Date(c.startsAt).getTime() > now
                  return (
                    <tr key={c.concertId}>
                      <td><b>{c.name}</b></td>
                      <td className="tnum muted">{dateTime(c.startsAt)} ~ {dateTime(c.endsAt).slice(11)}</td>
                      <td className="muted">{c.venue || '—'}</td>
                      <td><Badge tone={ended ? 'muted' : upcoming ? 'neutral' : 'ok'}>{ended ? '종료' : upcoming ? '예정' : '진행 중'}</Badge></td>
                      <td className="num">{c.anomalyUserCount == null ? <span className="na">—</span>
                        : c.anomalyUserCount > 0 ? <b style={{ color: COLOR.dangerText }}>{number(c.anomalyUserCount)}명</b> : <span className="muted">0명</span>}</td>
                      <td className="act"><button className="btn sm" onClick={() => onOpen(c.concertId, c.name)}>상세 조회</button></td>
                    </tr>
                  )
                })}</tbody>
              </table>
            </div>
          )}
      </Panel>
    </div>
  )
}
