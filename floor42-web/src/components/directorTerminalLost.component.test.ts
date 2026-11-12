// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { setLang } from '../i18n'
import DirectorTerminal from './DirectorTerminal.vue'

const view = { id: 'TERM-1', project: '/p', openedAt: 'x', running: true, costUsd: null, costAt: null, model: null, exitCode: null }
const api = vi.hoisted(() => ({
  terminals: vi.fn(),
  projects: vi.fn(async () => []),
  attachTerminal: vi.fn(),
}))
const xterm = vi.hoisted(() => ({ typed: null as null | ((d: string) => void) }))
vi.mock('../api/client', () => ({
  api,
  ApiError: class extends Error {},
}))
vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    cols = 100
    rows = 30
    loadAddon() {}
    open() {}
    focus() {}
    dispose() {}
    write() {}
    attachCustomKeyEventHandler() {}
    onData(f: (d: string) => void) {
      xterm.typed = f
    }
    onResize() {}
  },
}))
vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit() {} } }))
vi.mock('@xterm/xterm/css/xterm.css', () => ({}))

class FakeSocket {
  static OPEN = 1
  static CONNECTING = 0
  static all: FakeSocket[] = []
  readyState = 1
  binaryType = ''
  onopen: (() => void) | null = null
  onmessage: ((e: { data: unknown }) => void) | null = null
  onclose: ((e: { code: number }) => void) | null = null
  sent: string[] = []
  constructor() {
    FakeSocket.all.push(this)
  }
  send(s: string) {
    this.sent.push(s)
  }
  close() {
    this.readyState = 3
  }
  drop(code = 1006) {
    this.readyState = 3
    this.onclose?.({ code })
  }
}

beforeAll(() => setLang('en'))
beforeEach(() => {
  FakeSocket.all = []
  vi.stubGlobal('WebSocket', FakeSocket)
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  api.terminals.mockResolvedValue({ available: true, reason: null, terminals: [view] })
  api.attachTerminal.mockResolvedValue({ terminal: view, ticket: 't' })
})
afterEach(() => vi.unstubAllGlobals())

async function attached() {
  const w = mount(DirectorTerminal, { attachTo: document.body })
  await flushPromises()
  await w.findAll('button').find((b) => b.text() === 'Show')!.trigger('click')
  await flushPromises()
  return w
}

it('an unexpected close says the connection is lost and offers to reattach', async () => {
  const w = await attached()
  expect(w.find('.lost').exists()).toBe(false)
  FakeSocket.all[0].drop(1006)
  await flushPromises()
  expect(w.find('.lost').exists()).toBe(true)
  await w.find('.lost button').trigger('click')
  await flushPromises()
  expect(api.attachTerminal).toHaveBeenCalledTimes(2)
  expect(w.find('.lost').exists()).toBe(false)
  w.unmount()
})

it('a deliberate detach is not a lost connection', async () => {
  const w = await attached()
  const socket = FakeSocket.all[0]
  await w.findAll('button').find((b) => b.text() === 'Hide')!.trigger('click')
  socket.drop(1000)
  await flushPromises()
  expect(w.find('.lost').exists()).toBe(false)
  w.unmount()
})

it('typing on a closed socket says so instead of dropping the keys silently', async () => {
  const w = await attached()
  FakeSocket.all[0].readyState = 3
  xterm.typed?.('x')
  await flushPromises()
  expect(w.find('.lost').exists()).toBe(true)
  w.unmount()
})
