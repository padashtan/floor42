import { describe, expect, it } from 'vitest'
import type { ProjectView, TerminalView } from '../api/types'
import { clipboardKey, costText, inputMessage, parseMessage, projectChoices, resizeMessage, terminalUrl } from './terminal'

const view: TerminalView = {
  id: 'TERM-1',
  project: 'C:/work/app',
  openedAt: '2026-10-31T10:00:00Z',
  running: true,
  costUsd: null,
  costAt: null,
  model: null,
  exitCode: null,
}

describe('the director terminal', () => {
  it('reaches the screen on the page host with its ticket', () => {
    expect(terminalUrl({ protocol: 'http:', host: '127.0.0.1:7420' }, 'TERM-1', 'ab cd')).toBe(
      'ws://127.0.0.1:7420/ws/terminal/TERM-1?ticket=ab%20cd',
    )
  })

  it('passes keystrokes and sizes as they are', () => {
    expect(JSON.parse(inputMessage('/cost\r'))).toEqual({ type: 'input', data: '/cost\r' })
    expect(JSON.parse(resizeMessage(120, 32))).toEqual({ type: 'resize', columns: 120, rows: 32 })
  })

  it('reads only its own messages', () => {
    expect(parseMessage(JSON.stringify({ type: 'status', terminal: view }))?.terminal.id).toBe('TERM-1')
    expect(parseMessage('not json')).toBeNull()
    expect(parseMessage(JSON.stringify({ type: 'other' }))).toBeNull()
  })

  it('shows a cost only once Claude Code reported one', () => {
    expect(costText(view)).toBeNull()
    expect(costText({ ...view, costUsd: 0.04213 })).toBe('$0.0421')
    expect(costText({ ...view, costUsd: 0 })).toBe('$0.0000')
  })

  it('offers the active project first, then existing ones without duplicates', () => {
    const p = (path: string, exists = true, office = false): ProjectView => ({
      path,
      missions: [],
      lastTitle: '',
      lastMission: '',
      lastActivity: null,
      pinned: false,
      forgotten: false,
      office,
      exists,
      git: null,
    })
    expect(projectChoices('C:/a', [p('C:/b'), p('C:/a'), p('C:/gone', false), p('C:/idea', true, true)])).toEqual([
      'C:/a',
      'C:/b',
    ])
  })

  it('keeps Ctrl+C for Claude Code and copies with Ctrl+Shift+C', () => {
    expect(clipboardKey({ ctrlKey: true, shiftKey: false, key: 'c', type: 'keydown' })).toBeNull()
    expect(clipboardKey({ ctrlKey: true, shiftKey: true, key: 'C', type: 'keydown' })).toBe('copy')
    expect(clipboardKey({ ctrlKey: true, shiftKey: true, key: 'V', type: 'keydown' })).toBe('paste')
    expect(clipboardKey({ ctrlKey: true, shiftKey: true, key: 'V', type: 'keyup' })).toBeNull()
  })
})
