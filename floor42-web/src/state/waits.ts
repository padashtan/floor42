import { nowMs } from '../ui/clock'
import { reactive } from 'vue'
import { api, ApiError } from '../api/client'
import type { MissionProgress, ProgressItem } from '../api/types'
import { t, tr, dateLocale, type Key } from '../i18n'
import { badgedTitle, countdown, newWaits, stuckAgents, totals, waited } from '../ui/waits'

/**
 * What waits for the director, across missions, read from GET /api/progress every few seconds and shortly after any
 * live event: the Progress panel, the badge on its button, the count in the tab's title, the "?" over the agent of a
 * stuck mission, and the browser notifications the director allowed. A copy of the server's reading, nothing more.
 */
export const waits = reactive({
  missions: null as MissionProgress[] | null,
  error: null as string | null,
  forYou: 0,
  stuck: 0,
  stuckAgents: [] as string[],
  /** The director turned notifications on in this browser, and the browser allowed them. */
  notifying: false,
})

const NOTIFY_KEY = 'floor42.notify'
// Each read runs git on the worktrees: live events bring news sooner, the poll keeps timers and stalls honest.
const POLL_MS = 10000
let timer: number | undefined
let soon: number | undefined
let baseTitle = ''
let first = true
const notified = new Set<string>()

function stored(): boolean {
  try {
    return localStorage.getItem(NOTIFY_KEY) === 'on'
  } catch {
    return false
  }
}

function permitted(): boolean {
  return typeof Notification !== 'undefined' && Notification.permission === 'granted'
}

/** The reason codes of a risk line, each in the screen's language (the server sends codes only). */
export const riskReasons = (codes: string | null): string =>
  (codes ?? '').split(/[,\s]+/).filter(Boolean).map((c) => tr(`risk.reason.${c}`, c)).join(', ')

/** The sentence for one line of the panel, in the screen's language. Also the text of its notification. */
export function itemText(i: ProgressItem, now: number): string {
  const since = i.since ? Date.parse(i.since) : null
  // A workshop line without a count: git did not answer, so how much waits there is unknown (ADR-0055).
  const key = i.kind === 'WORKSHOP' && i.count === null ? 'progress.item.WORKSHOP_UNKNOWN' : `progress.item.${i.kind}`
  return t(key as Key, {
    ticket: i.ticket ?? '',
    agent: i.agent ?? '',
    text: i.kind === 'SESSION_RISK' ? riskReasons(i.text) : (i.text ?? ''),
    level: i.status ? tr(`risk.level.${i.status}`, i.status) : '',
    status: i.status ? tr(`progress.status.${i.status}`, i.status) : '',
    count: i.count ?? 0,
    ago: since === null ? '' : waited(now - since),
    at: since === null ? '' : new Date(since).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' }),
    seconds: i.runningSeconds ?? 0,
    work: i.status ? tr(`waits.work.${i.status}`, i.status) : '',
  })
}

function apply(missions: MissionProgress[]): void {
  waits.missions = missions
  waits.error = null
  const n = totals(missions)
  waits.forYou = n.forYou
  waits.stuck = n.stuck
  waits.stuckAgents = stuckAgents(missions)
  if (typeof document !== 'undefined') document.title = badgedTitle(baseTitle, n.forYou + n.stuck)
  const fresh = newWaits(missions, notified)
  // A tab just opened does not ring for everything already waiting.
  if (first) {
    first = false
    return
  }
  if (!waits.notifying || !permitted()) return
  const now = nowMs()
  for (const f of fresh) {
    const left = countdown(f.item.deadline, now)
    const body = itemText(f.item, now) + (left ? ` · ${t('waits.withdrawnIn', { left })}` : '')
    try {
      // One tag per item: several tabs replace each other's notification instead of piling them up.
      new Notification(`${f.mission} · ${f.item.kind === 'STUCK' ? t('waits.notifyStuck') : t('waits.notifyTitle')}`, {
        body,
        tag: f.key,
      })
    } catch {
      // Some browsers only notify from a service worker: the badge and the title still tell.
    }
  }
}

export async function loadWaits(): Promise<void> {
  try {
    apply(await api.progress())
  } catch (e) {
    waits.error = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

/** Asks Floor42 to look at a mission again; the progress it answers replaces the current one. */
export async function nudgeMission(mission: string): Promise<void> {
  try {
    apply(await api.nudge(mission))
  } catch (e) {
    waits.error = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

/** Reads again shortly: an event may have settled or added a wait. Several events in a row make one read. */
export function waitsChanged(): void {
  window.clearTimeout(soon)
  soon = window.setTimeout(() => void loadWaits(), 2000)
}

export function startWaits(): void {
  baseTitle = document.title
  waits.notifying = stored() && permitted()
  void loadWaits()
  timer = window.setInterval(() => void loadWaits(), POLL_MS)
}

export function stopWaits(): void {
  window.clearInterval(timer)
  window.clearTimeout(soon)
  document.title = baseTitle
}

/** The director's choice: notifications only once they asked for them and the browser allowed them. */
export async function setNotifying(on: boolean): Promise<void> {
  if (on && typeof Notification !== 'undefined' && Notification.permission === 'default') {
    await Notification.requestPermission()
  }
  waits.notifying = on && permitted()
  try {
    localStorage.setItem(NOTIFY_KEY, waits.notifying ? 'on' : 'off')
  } catch {
    // Kept for this page only.
  }
}

export function notificationsSupported(): boolean {
  return typeof Notification !== 'undefined'
}

export function notificationsDenied(): boolean {
  return typeof Notification !== 'undefined' && Notification.permission === 'denied'
}
