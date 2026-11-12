// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ContextMenu from './ContextMenu.vue'

/** The ARIA menu pattern (F4 of the UI audit): items are menuitem buttons, arrows move, Escape closes. */
describe('the context menu', () => {
  const open = async () => {
    const w = mount(ContextMenu, { props: { target: { kind: 'desk', id: 'desk' }, x: 10, y: 10 }, attachTo: document.body })
    await flushPromises()
    return w
  }

  it('is a menu of menuitem buttons, the first one focused', async () => {
    const w = await open()
    expect(w.find('[role="menu"]').exists()).toBe(true)
    const items = w.findAll('[role="menuitem"]')
    expect(items.length).toBeGreaterThan(1)
    expect(items.every((i) => i.element.tagName === 'BUTTON')).toBe(true)
    expect(document.activeElement).toBe(items[0].element)
    w.unmount()
  })

  it('moves with the arrows, wraps, jumps with Home and End', async () => {
    const w = await open()
    const items = w.findAll('[role="menuitem"]')
    const menu = w.find('[role="menu"]')
    await menu.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[1].element)
    await menu.trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(items.at(-1)!.element)
    await menu.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[0].element)
    await menu.trigger('keydown', { key: 'ArrowUp' })
    expect(document.activeElement).toBe(items.at(-1)!.element)
    w.unmount()
  })

  it('closes on Escape and picks on click', async () => {
    const w = await open()
    await w.find('[role="menu"]').trigger('keydown', { key: 'Escape' })
    expect(w.emitted('close')).toHaveLength(1)
    await w.findAll('[role="menuitem"]')[0].trigger('click')
    expect(w.emitted('pick')).toEqual([['desk']])
    w.unmount()
  })
})
