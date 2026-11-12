import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { BackLinks, DOCKED, type Focusable, FocusReturn, isMain, keptOf, menuAfterPress, superseded } from './panelLayout'
import { type PanelContext, panelTable } from './panelMounts'

const noop = () => {}
const context = (): PanelContext => ({
  selected: ref(null),
  deskFocus: ref(null),
  archive: ref(null),
  reportOf: ref(null),
  profileOf: ref(null),
  requestsOf: ref(null),
  outputsOf: ref(null),
  settingsFor: ref(null),
  openDesk: noop,
  selectAgent: noop,
  suspend: noop,
  resume: noop,
  dismiss: noop,
})
const ORDER = ['hire', 'settings', 'board', 'log', 'selection', 'away', 'desk']

describe('the layout rule', () => {
  it('the docked panels exist in the table, and only they are not main', () => {
    const table = panelTable(context())
    expect(DOCKED.filter((id) => !table.some((p) => p.id === id))).toEqual([])
    expect(table.filter((p) => !isMain(p.id)).map((p) => p.id).sort()).toEqual([...DOCKED].sort())
  })

  it('a panel that opens closes the main panels already open', () => {
    expect(superseded(['board', 'log', 'settings'], ['board', 'log'], ORDER)).toEqual(['board'])
    expect(superseded(['board', 'hire', 'log'], ['board', 'log'], ORDER)).toEqual(['board'])
  })

  it('never closes a docked panel, nor anything when nothing new opened', () => {
    expect(superseded(['log', 'selection', 'away', 'desk'], ['log', 'selection', 'away'], ORDER)).toEqual([])
    expect(superseded(['board', 'log'], ['board', 'log'], ORDER)).toEqual([])
    expect(superseded(['log'], ['board', 'log'], ORDER)).toEqual([])
  })

  it('opened together, the last of the table stays', () => {
    expect(superseded(['hire', 'desk', 'board'], [], ORDER).sort()).toEqual(['board', 'hire'])
  })

  it('leaves at most one main panel whatever the opening order', () => {
    const ids = ORDER.filter(isMain)
    for (const first of ids)
      for (const second of ids) {
        if (first === second) continue
        const closed = superseded([first, second], [first], ORDER)
        expect(closed).toEqual([first])
      }
  })
})

const control = (inMenu = false, connected = true) => {
  const calls: string[] = []
  const el: Focusable & { calls: string[] } = {
    calls,
    isConnected: connected,
    focus: () => void calls.push('focus'),
    closest: (selector) => (inMenu && selector === '.bar-dropdown' ? {} : null),
  }
  return el
}

describe('the focus return', () => {
  it('restores nothing when nothing was remembered', () => {
    const f = new FocusReturn()
    f.remember(null, () => null)
    expect(f.remembering).toBe(false)
    f.restore()
  })

  it('gives the focus back to the control that opened the panel, once', () => {
    const f = new FocusReturn()
    const opener = control()
    f.remember(opener, () => null)
    f.remember(control(), () => null)
    f.restore()
    f.restore()
    expect(opener.calls).toEqual(['focus'])
  })

  it('a control inside a closing menu gives way to the fallback; a control gone from the page is left alone', () => {
    const f = new FocusReturn()
    const button = control()
    f.remember(control(true), () => button)
    f.restore()
    expect(button.calls).toEqual(['focus'])
    const gone = control(false, false)
    f.remember(gone, () => null)
    f.restore()
    expect(gone.calls).toEqual([])
  })
})

describe('the same tick', () => {
  it('keeps the panel asked for last, whatever the table order', () => {
    const recency = (id: string) => ({ desk: 7, board: 5 })[id as 'desk' | 'board'] ?? 0
    expect(superseded(['board', 'desk'], [], ORDER, recency)).toEqual(['board'])
    expect(superseded(['board', 'desk'], [], ORDER, () => 0)).toEqual(['board'])
    expect(superseded(['board', 'desk'], [], ORDER, (id) => (id === 'board' ? 9 : 1))).toEqual(['desk'])
  })
})

describe('the way back from a child panel', () => {
  it('reopens the parent when the child closes and nothing else took its place', () => {
    const links = new BackLinks()
    links.replaced(keptOf(['caps', 'log'], ['settings']), ['settings'])
    expect(links.closed('caps', [])).toBe('settings')
    expect(links.closed('caps', [])).toBeNull()
  })

  it('does not reopen it when another panel opened meanwhile, nor for a panel that was not a child', () => {
    const links = new BackLinks()
    links.replaced('caps', ['settings'])
    expect(links.closed('caps', ['board'])).toBeNull()
    links.replaced('board', ['settings'])
    expect(links.closed('board', [])).toBeNull()
    links.replaced('caps', ['board'])
    expect(links.closed('caps', [])).toBeNull()
  })

  it('leads from agent settings back to the profile', () => {
    const links = new BackLinks()
    links.replaced('agent-settings', ['profile'])
    expect(links.closed('agent-settings', [])).toBe('profile')
  })
})

describe('the focus when the opener is gone', () => {
  it('goes to the fallback', () => {
    const f = new FocusReturn()
    f.remember(control(false, false), () => null)
    const bar = control()
    f.restore(() => bar)
    expect(bar.calls).toEqual(['focus'])
  })
})

describe('the context menu', () => {
  const open = { target: { kind: 'agent', id: 'A-1' }, x: 10, y: 20 }
  const inside = { closest: (s: string) => (s === '.panel.menu' ? {} : null) }
  const outside = { closest: () => null }

  it('closes on a press anywhere else', () => {
    expect(menuAfterPress(open, outside)).toBeNull()
    expect(menuAfterPress(open, null)).toBeNull()
  })

  it('stays open, as it was, for a press inside it, and stays closed when it was closed', () => {
    expect(menuAfterPress(open, inside)).toBe(open)
    expect(menuAfterPress(null, outside)).toBeNull()
  })
})

describe('the file viewer is a child of the lists it opens from', () => {
  it('leads back to the deliverables or the workshop, whichever it replaced', () => {
    const links = new BackLinks()
    links.replaced('viewer', ['workshop'])
    expect(links.closed('viewer', [])).toBe('workshop')
    links.replaced('viewer', ['deliverables'])
    expect(links.closed('viewer', [])).toBe('deliverables')
    links.replaced('viewer', ['board'])
    expect(links.closed('viewer', [])).toBeNull()
  })
})
