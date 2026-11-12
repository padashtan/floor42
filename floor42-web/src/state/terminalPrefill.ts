import { ref, shallowRef } from 'vue'
import { OneAtATime, prefillFor, prefillRefusal, type PrefillItem } from '../ui/prefill'
import { setPanel } from './openPanels'

/**
 * A command the catalogue asked to prepare in the director's terminal (ADR-0041, amendment of 2026-11-02). It lives in
 * this page only: the terminal panel takes it, types it through its own screen once Claude Code is ready, never with
 * Enter. There is no API behind it: nothing outside this page can ask for it.
 */
export interface PrefillRequest {
  text: string
  /** The catalogue item's project; null to use the terminal's current or last one. */
  project: string | null
  /** Its turn ({@link prefillTurn}): it releases only that, and types nothing once a newer click took it. */
  turn: number
}

export const pendingPrefill = shallowRef<PrefillRequest | null>(null)

/** The project of the terminal last shown in the panel, for an item that has no project of its own. */
export const lastTerminalProject = ref<string | null>(null)

/** Per terminal: whether the director typed on Claude Code's line since his last Enter. Absent means unknown. */
export const typedLine = new Map<string, boolean>()

/** One preparation at a time: two quick clicks would otherwise type "/a /b " without asking. */
export const prefillTurn = new OneAtATime()

export type PrefillAnswer = 'ok' | 'untrusted' | 'badName' | 'builtin' | 'busy'

/**
 * The catalogue's click: only a real event of the director's (not one a script dispatched), only a name the
 * allow-list accepts, and only while no other preparation runs. Opens the terminal panel, which does the rest and
 * gives the turn back ({@link prefillTurn}).
 */
export function requestPrefill(item: PrefillItem, project: string | null, event: { isTrusted: boolean }): PrefillAnswer {
  if (!event.isTrusted) return 'untrusted'
  const refused = prefillRefusal(item)
  const text = prefillFor(item)
  if (refused || !text) return refused ?? 'badName'
  const turn = prefillTurn.take()
  if (turn === null) return 'busy'
  pendingPrefill.value = { text, project, turn }
  setPanel('terminal', true)
  return 'ok'
}

/** The terminal panel takes the request once. */
export function takePrefill(): PrefillRequest | null {
  const r = pendingPrefill.value
  pendingPrefill.value = null
  return r
}
