// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import DeliveryHome from '../components/DeliveryHome.vue'
import { focusViewButton, openViewer, takeOpener, viewing, viewKey } from './viewer'

const file = { mission: 'M-1', agent: 'dev-1', path: 'notes.md', committed: false }
const opener = { from: 'deliverables' as const, key: viewKey('dev-1', 'notes.md', false), state: { section: 'agent' } }

describe('the way back from the file viewer (ADR-0051)', () => {
  it('gives the list that opened the viewer its state back, once, right after the viewer closes', async () => {
    openViewer(file, opener)
    expect(takeOpener('deliverables'), 'not while the viewer is open').toBeNull()
    viewing.value = null
    await nextTick()
    expect(takeOpener('workshop'), 'not to another list').toBeNull()
    expect(takeOpener('deliverables')).toEqual(opener)
    expect(takeOpener('deliverables'), 'taken once').toBeNull()
  })

  it('forgets the opener when the list comes back later than right after the close', async () => {
    openViewer(file, opener)
    viewing.value = null
    await nextTick()
    expect(takeOpener('deliverables', Date.now() + 10_000)).toBeNull()
  })

  it('puts the focus on the View button of the file, once the list shows it', async () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    setTimeout(() => {
      const b = document.createElement('button')
      b.dataset.viewKey = opener.key
      root.appendChild(b)
    }, 60)
    expect(await focusViewButton(() => root, opener.key)).toBe(true)
    expect((document.activeElement as HTMLElement).dataset.viewKey).toBe(opener.key)
    expect(await focusViewButton(() => root, 'dev-1:w:gone.md', 2)).toBe(false)
    root.remove()
  })
})

describe('the copy list', () => {
  it('lists one path twice for one agent without a duplicate key', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const f = { agent: 'dev-1', path: 'notes.md', writtenAs: 'notes.md', outcome: 'WRITTEN' as const, reason: null }
    const copy = (files: (typeof f | { outcome: 'SAME' })[]) => ({ folder: '/p/deliverables/M-1', files, leftOut: 0, truncated: false, at: '' })
    const w = mount(DeliveryHome, { props: { copy: copy([f, { ...f, outcome: 'SAME' as const }]) as never } })
    // Vue checks keys when it patches a list: the list is shown again with one more line.
    await w.setProps({ copy: copy([f, { ...f, outcome: 'SAME' as const }, { ...f, path: 'more.md' }]) as never })
    expect(warn.mock.calls.flat().join(' ')).not.toContain('Duplicate keys')
    warn.mockRestore()
    const keys = w.findAll('li').map((li) => (li.element as unknown as { __vnode?: { key: unknown } }).__vnode?.key)
    expect(keys).toHaveLength(3)
    expect(new Set(keys).size, 'every line has a key of its own').toBe(3)
  })
})
