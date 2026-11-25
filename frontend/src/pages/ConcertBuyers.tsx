import { ArrowLeft } from 'lucide-react'
import { PageHead } from '../ui'
import type { Session } from '../utils'
import ConcertUsers from './ConcertUsers'

interface Props {
  session: Session
  concertId: string
  concertName: string
  onBack: () => void
  onOpenUser: (userId: string, label: string) => void
}

export default function ConcertBuyers({ session, concertId, concertName, onBack, onOpenUser }: Props) {
  return (
    <>
      <PageHead title="참여 사용자" sub={concertName}>
        <button className="btn" onClick={onBack}><ArrowLeft size={14} />공연 상세</button>
      </PageHead>
      <ConcertUsers session={session} concertId={concertId} size={20} title="참여 사용자 목록"
        onOpenUser={onOpenUser} />
    </>
  )
}
