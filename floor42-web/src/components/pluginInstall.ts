import type { AssetChoice, MarketplaceView, PluginEntry, PluginPreview } from '../api/types'

/** Warnings about code that runs on the machine: the dialog asks the director to tick that they read them. */
export const CODE_WARNINGS = ['HOOKS', 'MCP', 'LSP', 'CONTENTS_UNKNOWN']

export function needsAcknowledge(p: PluginPreview): boolean {
  return p.warnings.some((w) => CODE_WARNINGS.includes(w))
}

/** Plugins of a marketplace whose name, description or publisher holds the text; at most `limit`. */
export function filterPlugins(m: MarketplaceView, text: string, limit = 50): PluginEntry[] {
  const q = text.trim().toLowerCase()
  const all = q
    ? m.plugins.filter((p) =>
        [p.name, p.description ?? '', p.publisher ?? ''].some((s) => s.toLowerCase().includes(q)),
      )
    : m.plugins
  return all.slice(0, limit)
}

export interface ChoiceGroup {
  key: string
  target: string
  plugin: string
  marketplace: string
  items: AssetChoice[]
}

/** Choices grouped by plugin and target, in the server's order. */
export function groupChoices(choices: AssetChoice[]): ChoiceGroup[] {
  const groups = new Map<string, ChoiceGroup>()
  for (const c of choices) {
    const key = `${c.target}:${c.plugin}@${c.marketplace}`
    let g = groups.get(key)
    if (!g) {
      g = { key, target: c.target, plugin: c.plugin, marketplace: c.marketplace, items: [] }
      groups.set(key, g)
    }
    g.items.push(c)
  }
  return [...groups.values()]
}

/** Every givable item of a group, to tick at once. */
export function givableIds(g: ChoiceGroup): string[] {
  return g.items.filter((i) => i.givable).map((i) => i.id)
}
