// settings-redesign: where each setting lives in the settings panel. Screen logic only: what is saved, and how, is the
// server's (Setting.java); this file only files every setting under one section, and filters them by a search.
import type { SettingView } from '../api/types'
import { t, tr } from '../i18n'

export type SettingKey = SettingView['key']

/**
 * Every key of SettingView's union, once: the type check fails when the union gains or loses one, and
 * settingsSections.test.ts compares the list with the constants of Setting.java.
 */
const KEYS: Record<SettingKey, true> = {
  PARALLEL_SESSIONS: true,
  PLAN_LIMIT_THRESHOLD: true,
  RETRO_MODEL: true,
  AGENT_LANGUAGE: true,
  DIRECTOR_PROFILE: true,
  SESSION_BUDGET_USD: true,
  OFFICE_THEME: true,
  SHELL_SANDBOX: true,
  SANDBOX_NETWORK: true,
  ACCEPTED_STREAM_LINES: true,
  STALL_MINUTES: true,
  MISSION_BUDGET_USD: true,
}
export const SETTING_KEYS = Object.keys(KEYS) as SettingKey[]

export const SECTION_IDS = ['general', 'missions', 'permissions', 'agents', 'safety', 'identity', 'advanced'] as const
export type SectionId = (typeof SECTION_IDS)[number]

/** A panel of the bar (ui/actions.ts) a line of the settings opens, when the value is edited there. */
export type LinkedPanel = 'caps' | 'sandbox' | 'sessions'

/** One line of the settings panel. */
export type SettingsItem =
  /** A company setting, journaled. `editedIn`: shown here, edited in that panel (its only editor). */
  | { kind: 'setting'; key: SettingKey; editedIn?: LinkedPanel }
  /** The language of this screen: this browser only, never journaled. */
  | { kind: 'language' }
  /** The guided first mission (ADR-0045): restart or skip it, in this browser. */
  | { kind: 'tutorial' }
  /** The default permission table for new missions. */
  | { kind: 'permissions' }
  /** The journal's backups. */
  | { kind: 'backups' }
  /** Unsafe mode (ADR-0063): the director's journaled choice to run a real shell without the sandbox. */
  | { kind: 'unsafe' }
  /** Agent identity (IAM mode): its switch, its state, its values. */
  | { kind: 'iam' }
  /** A panel of its own, opened from here. */
  | { kind: 'panel'; panel: Exclude<LinkedPanel, 'caps'> }

/** What the section list shows beside a section. */
export interface SectionCounts {
  matches: number
  changed: number
  unsaved: number
}

export interface SettingsSection {
  id: SectionId
  items: SettingsItem[]
}

const setting = (key: SettingKey, editedIn?: LinkedPanel): SettingsItem =>
  editedIn ? { kind: 'setting', key, editedIn } : { kind: 'setting', key }

/** The sections in their order. Every key of Setting.java sits in exactly one of them (settingsSections.test.ts). */
export const SECTIONS: readonly SettingsSection[] = [
  {
    id: 'general',
    items: [{ kind: 'language' }, setting('OFFICE_THEME'), { kind: 'tutorial' }, setting('DIRECTOR_PROFILE')],
  },
  {
    id: 'missions',
    items: [
      setting('MISSION_BUDGET_USD'),
      setting('SESSION_BUDGET_USD', 'caps'),
      setting('PARALLEL_SESSIONS'),
      setting('STALL_MINUTES'),
      setting('PLAN_LIMIT_THRESHOLD'),
    ],
  },
  { id: 'permissions', items: [{ kind: 'permissions' }] },
  { id: 'agents', items: [setting('AGENT_LANGUAGE'), setting('RETRO_MODEL')] },
  {
    id: 'safety',
    items: [
      setting('SHELL_SANDBOX'),
      setting('SANDBOX_NETWORK'),
      { kind: 'panel', panel: 'sandbox' },
      { kind: 'panel', panel: 'sessions' },
      { kind: 'unsafe' },
      { kind: 'backups' },
    ],
  },
  { id: 'identity', items: [{ kind: 'iam' }] },
  { id: 'advanced', items: [setting('ACCEPTED_STREAM_LINES')] },
]

/** A stable id for an item, for keys and anchors. */
export function itemId(item: SettingsItem): string {
  if (item.kind === 'setting') return item.key
  if (item.kind === 'panel') return `panel-${item.panel}`
  return item.kind
}

/** The sections, plus any key the server sends that no section names (a newer daemon): under Advanced, never lost. */
export function withUnfiled(sections: readonly SettingsSection[], keys: readonly string[]): SettingsSection[] {
  const filed = new Set(sections.flatMap((s) => s.items.map(itemId)))
  const extra = keys.filter((k) => !filed.has(k)).map((k) => setting(k as SettingKey))
  return sections.map((s) => (s.id === 'advanced' ? { ...s, items: [...s.items, ...extra] } : s))
}

/**
 * What the search looks into, in the screen's language: the name, the line and the long explanation shown for the
 * item, plus a setting's key and value. A key without texts (a newer daemon) is found by its key and value.
 */
export function searchText(item: SettingsItem, value = ''): string {
  switch (item.kind) {
    case 'setting':
      return [
        tr(`settings.${item.key}`, ''),
        tr(`sr.line.${item.key}`, ''),
        tr(`settings.${item.key}.hint`, ''),
        item.key,
        value,
      ].join(' ')
    case 'language':
      return [t('settings.language'), t('settings.languageHint'), t('lang.en'), t('lang.fr')].join(' ')
    case 'tutorial':
      return [t('tutorial.title'), t('tutorial.restart'), t('tutorial.skip')].join(' ')
    case 'permissions':
      return `${t('defaults.title')} ${t('defaults.hint')}`
    case 'iam':
      return `${t('idn.set.title')} ${t('idn.set.line')} ${t('idn.set.enabled')} ${t('idn.set.issuer')}`
    case 'unsafe':
      return `${t('unsafe.title')} ${t('unsafe.line')}`
    case 'backups':
      return `${t('backups.title')} ${t('backups.hint')}`
    case 'panel':
      return `${t(`sr.panel.${item.panel}`)} ${t(`sr.panel.${item.panel}.line`)}`
  }
}

export { filterSections, normalize } from '../ui/sectioned'

/** The next section for the arrow keys of the section list, wrapping around. */
export function step(current: SectionId, by: 1 | -1): SectionId {
  const i = SECTION_IDS.indexOf(current)
  return SECTION_IDS[(i + by + SECTION_IDS.length) % SECTION_IDS.length]
}
