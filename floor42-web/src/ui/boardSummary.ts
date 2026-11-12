import type { SessionView, TicketView } from '../api/types'

/** The board's delivery facts: tickets done and dropped, the elapsed time, the work of every session, the cost. */
export function boardSummary(all: TicketView[], sessions: SessionView[], now: number) {
  const starts = all.map((x) => x.startedAt).filter((s): s is string => !!s).map(Date.parse)
  const ends = all.map((x) => x.endedAt).filter((s): s is string => !!s).map(Date.parse)
  const work = sessions.reduce(
    (a, s) => a + Math.max(0, (s.endedAt ? Date.parse(s.endedAt) : now) - Date.parse(s.startedAt)),
    0,
  )
  const usd = sessions.map((s) => s.costUsdEstimated).filter((c): c is number => c !== null)
  return {
    done: all.filter((x) => x.status === 'DONE').length,
    dropped: all.filter((x) => x.status === 'CANCELLED').length,
    elapsed: starts.length && ends.length ? Math.max(...ends) - Math.min(...starts) : null,
    work,
    sessions: sessions.length,
    usd: usd.length ? usd.reduce((a, b) => a + b, 0) : null,
  }
}
