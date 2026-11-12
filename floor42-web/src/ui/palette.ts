import type { Key } from '../i18n'
import { ACTIONS, type Action } from './actions'
import { rank } from './fuzzy'

/**
 * What the command palette (Ctrl+K) offers: every action and panel of the registry, the rooms, the agents by name (to
 * open their profile) and the missions by id or title (to put on the board). Pure: the caller passes the lists and the
 * translation, so it is tested without a browser.
 */
export type PaletteKind = 'action' | 'command' | 'room' | 'agent' | 'mission'

export interface PaletteItem {
  kind: PaletteKind
  /** The action's id, the room's index, the agent's or the mission's id. */
  id: string
  label: string
  detail: string
  /** The key that does the same outside the palette. */
  key?: string
  /** Opens a page in a new tab. */
  href?: string
}

/** A command the palette offers outside the bar (the tutorial's restart and skip): a label and one sentence. */
export interface PaletteCommand {
  id: string
  label: Key
  title: Key
}

/** The tutorial's commands (ADR-0045): App.vue runs them by id. */
export const COMMANDS: readonly PaletteCommand[] = [
  { id: 'tutorialRestart', label: 'tutorial.restart', title: 'tutorial.restartTitle' },
  { id: 'tutorialSkip', label: 'tutorial.skip', title: 'tutorial.skipTitle' },
]

export interface PaletteSources {
  actions?: readonly Action[]
  commands?: readonly PaletteCommand[]
  rooms: readonly { id: string; name: string }[]
  agents: readonly { id: string; name: string; role: string }[]
  missions: readonly { id: string; title: string; status: string }[]
  t: (key: Key, params?: Record<string, string | number>) => string
  /** A room's name in the screen's language. */
  room: (id: string, fallback: string) => string
}

/** Rooms 1 to 5 have a key of their own (App.vue's keyboard handler). */
const ROOM_KEYS = 5

export function paletteItems(s: PaletteSources): PaletteItem[] {
  const actions = (s.actions ?? ACTIONS).map(
    (a): PaletteItem => ({
      kind: 'action',
      id: a.id,
      label: s.t(a.label),
      detail: a.title ? s.t(a.title) : '',
      key: a.key,
      href: a.href,
    }),
  )
  const commands = (s.commands ?? COMMANDS).map(
    (c): PaletteItem => ({ kind: 'command', id: c.id, label: s.t(c.label), detail: s.t(c.title) }),
  )
  const rooms = s.rooms.map(
    (r, i): PaletteItem => ({
      kind: 'room',
      id: String(i),
      label: s.room(r.id, r.name),
      detail: s.t('palette.goRoom'),
      key: i < ROOM_KEYS ? String(i + 1) : undefined,
    }),
  )
  const agents = s.agents.map(
    (a): PaletteItem => ({ kind: 'agent', id: a.id, label: a.name, detail: s.t('palette.openProfile', { role: a.role }) }),
  )
  // The latest missions first: they are the ones the director looks for.
  const missions = [...s.missions].reverse().map(
    (m): PaletteItem => ({
      kind: 'mission',
      id: m.id,
      label: `${m.id} · ${m.title}`,
      detail: s.t('palette.onBoard', { status: m.status }),
    }),
  )
  return [...actions, ...commands, ...rooms, ...agents, ...missions]
}

/** The items matching the query, best first. Without a query: actions and rooms, then agents, then missions. */
export function searchPalette(items: readonly PaletteItem[], query: string, limit = 40): PaletteItem[] {
  return rank(items, query, (i) => [i.label, i.detail, i.id, i.key ?? ''], limit)
}
