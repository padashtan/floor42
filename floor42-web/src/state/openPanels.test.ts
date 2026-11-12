import { describe, expect, it } from 'vitest'
import { closeLastPanel, openedAfter, panelFlag, panels, setPanel } from './openPanels'

describe('registry panels', () => {
  it('closes the last panel opened first, skipping those already closed by hand', () => {
    setPanel('terminal', true)
    setPanel('deliverables', true)
    setPanel('marketplace', true)
    panels.marketplace = false
    expect(closeLastPanel()).toBe(true)
    expect(panels.deliverables).toBe(false)
    expect(panels.terminal).toBe(true)
    expect(closeLastPanel()).toBe(true)
    expect(panels.terminal).toBe(false)
    expect(closeLastPanel()).toBe(false)
  })

  it('moves a panel opened again to the top', () => {
    setPanel('a', true)
    setPanel('b', true)
    setPanel('a', true)
    closeLastPanel()
    expect(panels).toMatchObject({ a: false, b: true })
    closeLastPanel()
  })

  it('gives the open flag of a panel as a ref over the same record', () => {
    const board = panelFlag('board')
    expect(board.value).toBe(false)
    board.value = true
    expect(panels.board).toBe(true)
    expect(closeLastPanel()).toBe(true)
    expect(board.value).toBe(false)
  })

  it('tells which of two open panels was opened last', () => {
    setPanel('settings', true)
    setPanel('caps', true)
    expect(openedAfter('caps', 'settings')).toBe(true)
    expect(openedAfter('settings', 'caps')).toBe(false)
    setPanel('settings', true)
    expect(openedAfter('caps', 'settings')).toBe(false)
    setPanel('settings', false)
    expect(openedAfter('caps', 'settings')).toBe(false)
    setPanel('caps', false)
  })
})
