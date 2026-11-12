import type { Key } from '../i18n'

/**
 * Every action and panel of the top bar, in one list: the bar's menus, its direct buttons, the command palette (Ctrl+K)
 * and the single-key shortcuts are all read from here. A new panel adds one line below; App.vue shows it with
 * `v-if="panels.<id>"` and closes it with `panels.<id> = false` (Escape closes the last one opened), unless App.vue
 * binds its own handler in `runAction`. Pure data: no state, so the grouping is tested without a browser.
 */
export type Group = 'work' | 'team' | 'system'

export interface Action {
  /** Stable id: the palette, the handler and the panel's open flag use it. */
  id: string
  group: Group
  /** Short label for a menu, a direct button and the palette, without the shortcut. */
  label: Key
  /** One sentence: the button's tooltip and the palette's second line. */
  title?: Key
  /** A single key that runs the action when nothing is being typed; shown next to it everywhere. */
  key?: string
  /** A button of its own in the bar, outside the menus: the few the director uses all day. */
  direct?: boolean
  /** Opens this page in a new tab instead of running anything. */
  href?: string
}

export const GROUPS: readonly Group[] = ['work', 'team', 'system']

export const ACTIONS: readonly Action[] = [
  { id: 'desk', group: 'work', label: 'palette.a.desk', title: 'palette.t.desk', key: 'D', direct: true },
  { id: 'board', group: 'work', label: 'palette.a.board', title: 'top.boardTitle', key: 'B', direct: true },
  { id: 'progress', group: 'work', label: 'palette.a.progress', title: 'top.progressTitle', key: 'P', direct: true },
  { id: 'resume', group: 'work', label: 'resume.top', title: 'resume.topTitle' },
  { id: 'workshop', group: 'work', label: 'palette.a.workshop', title: 'top.workshopTitle', key: 'W' },
  { id: 'library', group: 'work', label: 'library.top', title: 'library.topTitle', key: 'L' },
  { id: 'meeting', group: 'work', label: 'palette.a.meeting', title: 'palette.t.meeting', key: 'M' },
  { id: 'callMeeting', group: 'work', label: 'palette.a.callMeeting', title: 'palette.t.callMeeting' },
  { id: 'workflows', group: 'work', label: 'workflows.top', title: 'workflows.topTitle' },
  { id: 'deliverables', group: 'work', label: 'deliverables.top', title: 'deliverables.topTitle' },
  { id: 'prove', group: 'work', label: 'prove.top', title: 'prove.topTitle' },
  { id: 'archive', group: 'work', label: 'palette.a.archive', title: 'palette.t.archive' },
  { id: 'tutorial', group: 'work', label: 'tutorial.top', title: 'tutorial.topTitle' },
  { id: 'hire', group: 'team', label: 'top.hire', title: 'top.hireTitle' },
  { id: 'core', group: 'team', label: 'core.dismissAll', title: 'core.dismissAllTitle' },
  { id: 'console', group: 'system', label: 'palette.a.console', title: 'top.apiTitle', key: 'C' },
  { id: 'docs', group: 'system', label: 'top.docs', title: 'top.docsTitle', href: '/q/swagger-ui' },
  { id: 'costs', group: 'system', label: 'cost.top', title: 'cost.topTitle' },
  { id: 'caps', group: 'system', label: 'top.caps', title: 'top.capsTitle' },
  { id: 'sandbox', group: 'system', label: 'sandbox.top', title: 'sandbox.topTitle' },
  { id: 'skills', group: 'system', label: 'skills.top', title: 'skills.topTitle' },
  { id: 'terminal', group: 'system', label: 'top.terminal', title: 'top.terminalTitle' },
  { id: 'sessions', group: 'system', label: 'sessions.top', title: 'sessions.topTitle' },
  { id: 'identities', group: 'system', label: 'idn.top', title: 'idn.topTitle' },
  { id: 'usage', group: 'system', label: 'palette.a.usage', title: 'palette.t.usage', key: 'U' },
  { id: 'settings', group: 'system', label: 'top.settings', title: 'top.settingsTitle' },
]

/** The action a single key runs, case ignored; undefined when the key is free. */
export function byKey(key: string, actions: readonly Action[] = ACTIONS): Action | undefined {
  const k = key.toUpperCase()
  return actions.find((a) => a.key === k)
}

/** The bar's layout: the direct buttons in their order, then one menu per group holding all its actions, direct ones too. */
export function grouped(actions: readonly Action[] = ACTIONS): {
  direct: Action[]
  menus: { group: Group; items: Action[] }[]
} {
  return {
    direct: actions.filter((a) => a.direct),
    menus: GROUPS.map((group) => ({ group, items: actions.filter((a) => a.group === group) })),
  }
}
