// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { en } from '../i18n/en'
import { fr } from '../i18n/fr'
import { office } from '../state/office'
import SignInCard from './SignInCard.vue'
import ToastStack from './ToastStack.vue'

/** WCAG 2.2 AA: live regions, names of icon-only buttons, the sign-in dialog's focus kept inside. */
describe('accessibility roles and names', () => {
  afterEach(() => {
    office.toasts.splice(0)
    vi.unstubAllGlobals()
  })

  it('announces toasts politely and incidents as alerts, the close button named', () => {
    office.toasts.push({ id: 'a', text: 'Hello', question: null }, { id: 'b', text: 'Boom', question: null, error: true })
    const w = mount(ToastStack)
    expect(w.get('.toasts').attributes('role')).toBe('status')
    const toasts = w.findAll('.toast')
    expect(toasts[0]!.attributes('role')).toBeUndefined()
    expect(toasts[1]!.attributes('role')).toBe('alert')
    expect(w.get('button.close').attributes('aria-label')).toBe(en['toast.close'])
  })

  it('has the scene and close texts in both catalogues', () => {
    for (const k of ['scene.label', 'toast.close'] as const) {
      expect(en[k]).toBeTruthy()
      expect(fr[k]).toBeTruthy()
    }
  })

  it('sign-in card: Tab stays inside the dialog, the outcome is announced', async () => {
    vi.stubGlobal('fetch', vi.fn(async (u: string) => ({ status: u === '/auth/open' ? 202 : 404 })))
    const w = mount(SignInCard, { attachTo: document.body })
    await flushPromises()
    const button = w.get('button').element as HTMLButtonElement
    button.focus()
    await w.get('button').trigger('click')
    await flushPromises()
    expect(w.get('[role=status]').text()).toBe(en['d12.login.opened'])
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    button.dispatchEvent(tab)
    expect(tab.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(button)
    w.unmount()
  })
})
