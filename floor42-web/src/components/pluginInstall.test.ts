import { describe, expect, it } from 'vitest'
import type { AssetChoice, MarketplaceView, PluginPreview } from '../api/types'
import { filterPlugins, givableIds, groupChoices, needsAcknowledge } from './pluginInstall'

function preview(warnings: string[]): PluginPreview {
  return {
    target: 'FLOOR42',
    action: 'INSTALL',
    plugin: 'p',
    marketplace: 'm',
    source: null,
    publisher: null,
    version: null,
    license: null,
    description: null,
    contents: null,
    warnings,
    commands: [],
    selectedBy: [],
    refused: null,
    refusal: null,
    digest: 'd',
  }
}

function choice(id: string, plugin: string, givable: boolean): AssetChoice {
  return {
    id,
    target: 'FLOOR42',
    plugin,
    marketplace: 'm',
    kind: 'SKILL',
    name: id,
    description: null,
    givable,
    reason: givable ? null : 'declaresHooks',
    selected: false,
  }
}

describe('plugin install', () => {
  it('asks the director to acknowledge code that runs on the machine', () => {
    expect(needsAcknowledge(preview(['NOT_UNTIL_SELECTED']))).toBe(false)
    expect(needsAcknowledge(preview(['HOOKS']))).toBe(true)
    expect(needsAcknowledge(preview(['CONTENTS_UNKNOWN']))).toBe(true)
  })

  it('filters a marketplace by name, description or publisher', () => {
    const m: MarketplaceView = {
      name: 'm',
      source: null,
      owner: null,
      knownIn: [],
      official: false,
      listedFrom: 'claude',
      listedAt: null,
      refreshable: false,
      plugins: [
        { name: 'helper', description: 'Tidies', publisher: 'Acme', version: null, category: null, sourceKind: 'relative', installedIn: [] },
        { name: 'other', description: null, publisher: 'Zed', version: null, category: null, sourceKind: 'url', installedIn: [] },
      ],
    }
    expect(filterPlugins(m, 'tidi').map((p) => p.name)).toEqual(['helper'])
    expect(filterPlugins(m, 'zed').map((p) => p.name)).toEqual(['other'])
    expect(filterPlugins(m, '', 1)).toHaveLength(1)
  })

  it('groups choices by plugin and ticks only what Floor42 gives', () => {
    const groups = groupChoices([choice('a', 'p1', true), choice('b', 'p1', false), choice('c', 'p2', true)])
    expect(groups.map((g) => g.plugin)).toEqual(['p1', 'p2'])
    expect(givableIds(groups[0])).toEqual(['a'])
  })
})
