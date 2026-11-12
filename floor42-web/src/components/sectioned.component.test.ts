// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { setLang } from '../i18n'
import MeasureFigure from './MeasureFigure.vue'
import SectionedPanel from './SectionedPanel.vue'

beforeAll(() => setLang('en'))

describe('MeasureFigure', () => {
  const cases = [
    ['MEASURED', 'measured'],
    ['ESTIMATED', 'estimated'],
    ['REPORTED', 'reported'],
    ['SIMULATED', 'simulated'],
  ] as const

  it.each(cases)('shows the %s origin as "%s", with the whole basis in its tooltip', (basis, label) => {
    const w = mount(MeasureFigure, { props: { measure: { value: 1.5, basis }, kind: 'money' } })
    const tag = w.find('.origin')
    expect(tag.text()).toBe(label)
    expect(tag.classes()).toContain(basis.toLowerCase())
    expect(tag.attributes('title')).toBeTruthy()
  })

  it('shows an estimate with a tilde and a missing figure as not reported, never as zero', () => {
    expect(mount(MeasureFigure, { props: { measure: { value: 1.5, basis: 'ESTIMATED' }, kind: 'money' } }).find('.value').text()).toContain('≈')
    expect(mount(MeasureFigure, { props: { measure: { value: null, basis: 'MEASURED' }, kind: 'time' } }).find('.value').text()).toBe('not reported')
  })
})

describe('SectionedPanel keyboard', () => {
  const Host = defineComponent({
    setup() {
      const current = ref('a')
      const sections = [
        { id: 'a', label: 'Alpha' },
        { id: 'b', label: 'Beta' },
        { id: 'c', label: 'Gamma' },
      ]
      return () =>
        h(SectionedPanel, { title: 'T', sections, current: current.value, idPrefix: 'x', onChoose: (id: string) => (current.value = id) }, () => 'content')
    },
  })
  const tab = (w: ReturnType<typeof mountHost>, id: string) => w.find(`[data-section-tab="${id}"]`)
  const mountHost = () => mount(Host, { attachTo: document.body })

  it('exposes a tab list with one selected tab and a roving tabindex, focus on the current one', () => {
    const w = mountHost()
    expect(w.find('[role="tablist"]').exists()).toBe(true)
    expect(tab(w, 'a').attributes('aria-selected')).toBe('true')
    expect(tab(w, 'b').attributes('aria-selected')).toBe('false')
    expect(tab(w, 'a').attributes('tabindex')).toBe('0')
    expect(tab(w, 'b').attributes('tabindex')).toBe('-1')
    expect(document.activeElement).toBe(tab(w, 'a').element)
    expect(w.find('[role="tabpanel"]').attributes('aria-labelledby')).toBe('x-tab-a')
    w.unmount()
  })

  it('moves with Left and Right in the row layout of a narrow screen, announced as horizontal', async () => {
    const original = window.matchMedia
    window.matchMedia = ((query: string) => ({
      matches: true,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia
    const w = mountHost()
    await w.vm.$nextTick()
    expect(w.find('[role="tablist"]').attributes('aria-orientation')).toBe('horizontal')
    await tab(w, 'a').trigger('keydown', { key: 'ArrowRight' })
    await w.vm.$nextTick()
    expect(tab(w, 'b').attributes('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(tab(w, 'b').element)
    await tab(w, 'b').trigger('keydown', { key: 'ArrowLeft' })
    await tab(w, 'a').trigger('keydown', { key: 'ArrowLeft' })
    await w.vm.$nextTick()
    expect(tab(w, 'c').attributes('aria-selected')).toBe('true')
    w.unmount()
    window.matchMedia = original
  })

  it('moves with the arrows, Home and End, the selection and the focus following', async () => {
    const w = mountHost()
    const press = async (from: string, key: string) => {
      await tab(w, from).trigger('keydown', { key })
      await w.vm.$nextTick()
    }
    await press('a', 'ArrowDown')
    expect(tab(w, 'b').attributes('aria-selected')).toBe('true')
    expect(tab(w, 'b').attributes('tabindex')).toBe('0')
    expect(tab(w, 'a').attributes('tabindex')).toBe('-1')
    expect(document.activeElement).toBe(tab(w, 'b').element)
    await press('b', 'ArrowUp')
    await press('a', 'ArrowUp')
    expect(tab(w, 'c').attributes('aria-selected')).toBe('true')
    await press('c', 'Home')
    expect(tab(w, 'a').attributes('aria-selected')).toBe('true')
    await press('a', 'End')
    expect(tab(w, 'c').attributes('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(tab(w, 'c').element)
    w.unmount()
  })
})
