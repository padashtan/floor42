import type { MissionProgress, ProgressItem, StallView } from '../api/types'

/**
 * Timers on what waits for the director, read from the progress the server computed: how long each wait has lasted and,
 * for an agent's ask_director question, how long until Floor42 withdraws it. Pure functions, so they are tested without
 * a browser; the clock is always passed in.
 */

/** How long something has waited: 45 s, 12 min, 2 h 05, 3 d 4 h. */
export function waited(ms: number): string {
  if (!(ms >= 0)) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} min`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} h ${String(m % 60).padStart(2, '0')}`
  return `${Math.floor(h / 24)} d ${h % 24} h`
}

/** A countdown to a deadline, to the second: 29:59, then 0:00 once passed; null without a deadline. */
export function countdown(deadline: string | null, now: number): string | null {
  if (!deadline) return null
  const left = Math.max(0, Math.floor((Date.parse(deadline) - now) / 1000))
  const h = Math.floor(left / 3600)
  const m = Math.floor((left % 3600) / 60)
  const s = left % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`
}

/**
 * The mission waits for the director, not for a machine: the finished work of an agent is held in the workshop, to
 * accept or reject. It reads as a call to act (amber, with the way in), never as a frozen or disabled mission.
 */
export function stallNeedsYou(s: StallView | undefined): boolean {
  return s?.state === 'WAITING' && s.reason === 'HELD_PATCH'
}

/** How long the item has waited, or null when the server did not say since when. */
export function waitedFor(i: ProgressItem, now: number): string | null {
  return i.since ? waited(now - Date.parse(i.since)) : null
}

/**
 * A stable name for one thing that waits, the same in every tab: the browser notification's tag, so several tabs
 * replace each other's notification instead of piling them up.
 */
export function waitKey(mission: string, i: ProgressItem): string {
  return ['floor42', mission, i.kind, i.question ?? i.ticket ?? i.agent ?? ''].join(':')
}

export interface WaitTotals {
  /** Everything that waits for the director, across missions. */
  forYou: number
  /** Missions stuck by the dispatcher's own rule. */
  stuck: number
}

export function totals(missions: MissionProgress[]): WaitTotals {
  return {
    forYou: missions.reduce((n, m) => n + m.forYou.length, 0),
    stuck: missions.filter((m) => m.stall?.state === 'STUCK').length,
  }
}

/** The tab's title with the number of waits in front, "(3) Floor42"; the plain title when nothing waits. */
export function badgedTitle(base: string, count: number): string {
  const plain = base.replace(/^\(\d+\)\s*/, '')
  return count > 0 ? `(${count}) ${plain}` : plain
}

/** The agents a stuck mission would put to work: the scene shows a "?" over them. */
export function stuckAgents(missions: MissionProgress[]): string[] {
  return [
    ...new Set(missions.filter((m) => m.stall?.state === 'STUCK' && m.stall.agent).map((m) => m.stall.agent!)),
  ]
}

/**
 * The items to notify about: those not notified yet in this tab. The caller keeps `seen`; items that no longer wait are
 * dropped from it so a new wait of the same kind notifies again.
 */
export function newWaits(
  missions: MissionProgress[],
  seen: Set<string>,
): { key: string; mission: string; item: ProgressItem }[] {
  const now = new Set<string>()
  const fresh: { key: string; mission: string; item: ProgressItem }[] = []
  for (const m of missions) {
    for (const i of [...m.forYou, ...m.stuck]) {
      const key = waitKey(m.mission, i)
      now.add(key)
      if (!seen.has(key)) fresh.push({ key, mission: m.mission, item: i })
    }
  }
  for (const k of [...seen]) if (!now.has(k)) seen.delete(k)
  fresh.forEach((f) => seen.add(f.key))
  return fresh
}
