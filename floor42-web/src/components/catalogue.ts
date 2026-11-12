import type { InstalledItem, Reach, SuggestionItem } from '../api/types'

/**
 * Grouping and filtering of the skills catalogue (W3), kept apart from the panel so it is tested without Vue. Nothing
 * here changes a verdict: it only chooses what is shown.
 */

export type KindFilter = 'ALL' | 'SKILL' | 'AGENT' | 'PLUGIN' | 'MCP'
export type ReachFilter = 'ALL' | Reach

export interface Filter {
  text: string
  kind: KindFilter
  reach: ReachFilter
}

export const NO_FILTER: Filter = { text: '', kind: 'ALL', reach: 'ALL' }

/** One source: the user level, the project level, or one plugin (its own line first, then its skills and agents). */
export interface Group {
  key: string
  level: InstalledItem['level']
  plugin: string | null
  items: InstalledItem[]
}

function matchesText(text: string, ...fields: (string | null | undefined)[]): boolean {
  const q = text.trim().toLowerCase()
  if (!q) return true
  return fields.some((f) => f != null && f.toLowerCase().includes(q))
}

export function matches(item: InstalledItem, f: Filter): boolean {
  if (f.kind !== 'ALL' && f.kind !== item.kind) return false
  if (f.reach !== 'ALL' && f.reach !== item.state) return false
  return matchesText(f.text, item.name, item.description, item.path, item.plugin)
}

const LEVEL_ORDER: Record<InstalledItem['level'], number> = { USER: 0, PROJECT: 1, PLUGIN: 2 }
const KIND_ORDER: Record<InstalledItem['kind'], number> = { PLUGIN: 0, SKILL: 1, AGENT: 2 }

/**
 * The items that pass the filter, grouped by source: user, project, then each plugin by name. A plugin whose own line
 * is filtered out still shows the skills or agents of it that pass.
 */
export function groupInstalled(items: InstalledItem[], f: Filter = NO_FILTER): Group[] {
  const groups = new Map<string, Group>()
  for (const item of items) {
    if (!matches(item, f)) continue
    const key = item.level === 'PLUGIN' ? `plugin:${item.plugin ?? ''}` : item.level.toLowerCase()
    let g = groups.get(key)
    if (!g) {
      g = { key, level: item.level, plugin: item.level === 'PLUGIN' ? item.plugin : null, items: [] }
      groups.set(key, g)
    }
    g.items.push(item)
  }
  for (const g of groups.values()) {
    g.items.sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind] || a.name.localeCompare(b.name))
  }
  return [...groups.values()].sort(
    (a, b) => LEVEL_ORDER[a.level] - LEVEL_ORDER[b.level] || (a.plugin ?? '').localeCompare(b.plugin ?? ''),
  )
}

/** How many items are in each state, over everything installed (not the filtered view). */
export function counts(items: { state: Reach }[]): Record<Reach, number> {
  const c: Record<Reach, number> = { AVAILABLE: 0, NOT_AVAILABLE: 0, UNKNOWN: 0 }
  for (const i of items) c[i.state]++
  return c
}

export function filterSuggestions(list: SuggestionItem[], f: Filter): SuggestionItem[] {
  return list.filter((s) => {
    if (f.kind !== 'ALL' && f.kind.toLowerCase() !== s.kind) return false
    if (f.reach !== 'ALL' && f.reach !== s.state) return false
    return matchesText(f.text, s.name, s.source, ...Object.values(s.brings))
  })
}

/** A catalogue text in the screen's language, else English, else any. */
export function localText(texts: Record<string, string>, lang: string): string {
  return texts[lang] ?? texts.en ?? Object.values(texts)[0] ?? ''
}
