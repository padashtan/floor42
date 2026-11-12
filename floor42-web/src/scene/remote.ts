import type { AgentStatus } from '../api/types'

// Pure decor rules for "remote today": no journal event, no server fact. A display choice per agent and local day.

/** The local calendar day, "2026-11-04": the draw changes at the director's midnight, never within a day. */
export function dayKey(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** FNV-1a: a stable 32-bit hash of the agent and the day, the same on every screen and after a reload. */
function hash(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 0x01000193) >>> 0
  return h
}

/** Whether the agent is drawn as working from home on this day: about one in four. */
export function remoteToday(agentId: string, day: string): boolean {
  return hash(`${day}|${agentId}`) % 4 === 0
}

/** The agents drawn remote: none when the director turned it off; `forced` (a browser-test hook) replaces the draw. */
export function remoteSet(ids: string[], day: string, on: boolean, forced: string[] = []): Set<string> {
  if (!on) return new Set()
  return new Set(ids.filter((id) => (forced.length ? forced.includes(id) : remoteToday(id, day))))
}

/** What a webcam tile shows of the hands: the same recorded states as the office figure, nothing more. */
export type TileState = 'typing' | 'reading' | 'idle'

/** Typing only while a tool is executing; reading during a review; idle otherwise. */
export function tileState(status: AgentStatus): TileState {
  if (status === 'EXECUTING') return 'typing'
  return status === 'REVIEWING' ? 'reading' : 'idle'
}

/** The tiles of the strip: at most `max`, the rest counted. */
export function stripOf<T>(tiles: T[], max = 4): { shown: T[]; more: number } {
  return { shown: tiles.slice(0, max), more: Math.max(0, tiles.length - max) }
}
