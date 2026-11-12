// @vitest-environment happy-dom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ask, askReason, confirmed, pending } from '../state/confirm'
import { panels, setPanel } from '../state/openPanels'
import ConfirmDialog from './ConfirmDialog.vue'
import SettingsPanel from './SettingsPanel.vue'
import TutorialOffer from './TutorialOffer.vue'

const api = vi.hoisted(() => ({ settings: vi.fn(async () => []) }))
const tutorial = vi.hoisted(() => ({ markOffered: vi.fn(), startTutorial: vi.fn() }))
vi.mock('../state/tutorial', async (original) => ({ ...(await original<object>()), ...tutorial }))
vi.mock('../api/client', () => ({
  api,
  ApiError: class ApiError extends Error {
    needsLogin = false
  },
}))

/** The page's one confirmation (F9 of the UI audit): modal, its focus kept inside, Escape its own, focus given back. */
describe('the confirmation dialog', () => {
  const mounted: VueWrapper[] = []
  const attach = <T extends VueWrapper>(w: T): T => {
    mounted.push(w)
    return w
  }

  afterEach(async () => {
    pending.value?.settle(null)
    await flushPromises()
    for (const w of mounted.splice(0)) w.unmount()
    for (const id of Object.keys(panels)) setPanel(id as keyof typeof panels, false)
    document.body.innerHTML = ''
  })

  const opener = () => {
    const button = document.createElement('button')
    button.textContent = 'Dismiss'
    document.body.appendChild(button)
    button.focus()
    return button
  }
  const press = (key: string, shiftKey = false) => {
    const target = document.activeElement ?? document.body
    target.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }))
  }
  const dialog = () => document.querySelector<HTMLElement>('.confirm-dialog')!

  it('keeps Tab and Shift+Tab inside, Escape cancels, and the focus goes back to the opener', async () => {
    const button = opener()
    attach(mount(ConfirmDialog, { attachTo: document.body }))
    const answer = confirmed({ message: 'Sure?', danger: true })
    await flushPromises()
    const [cancel, ok] = [...dialog().querySelectorAll('button')]
    expect(document.activeElement).toBe(cancel)
    press('Tab')
    expect(document.activeElement).toBe(ok)
    press('Tab')
    expect(document.activeElement).toBe(cancel)
    press('Tab', true)
    expect(document.activeElement).toBe(ok)
    press('Escape')
    expect(await answer).toBe(false)
    await flushPromises()
    expect(document.querySelector('.confirm-dialog')).toBeNull()
    expect(document.activeElement).toBe(button)
  })

  it('a required text cannot be confirmed empty, by the button or by Enter', async () => {
    opener()
    attach(mount(ConfirmDialog, { attachTo: document.body }))
    const answer = ask({ message: 'New file', input: { label: 'Path', required: true } })
    await flushPromises()
    const field = dialog().querySelector('input')!
    const ok = dialog().querySelectorAll('button')[1]!
    expect(document.activeElement).toBe(field)
    expect(ok.disabled).toBe(true)
    press('Enter')
    await flushPromises()
    expect(pending.value).not.toBeNull()
    field.value = 'notes.md'
    field.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(ok.disabled).toBe(false)
    press('Enter')
    expect(await answer).toBe('notes.md')
  })

  it('a reason is required: emptied, it cannot be confirmed', async () => {
    opener()
    attach(mount(ConfirmDialog, { attachTo: document.body }))
    const answer = askReason({ message: 'Dismiss?', danger: true, reason: { default: 'not needed' } })
    await flushPromises()
    const field = dialog().querySelector('input')!
    const ok = dialog().querySelectorAll('button')[1]!
    field.value = '  '
    field.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(ok.disabled).toBe(true)
    field.focus()
    press('Enter')
    await flushPromises()
    expect(pending.value).not.toBeNull()
    field.value = 'left the team'
    field.dispatchEvent(new Event('input'))
    await flushPromises()
    ok.click()
    expect(await answer).toBe('left the team')
  })

  it('a reason left as shown is recorded as its English default', async () => {
    opener()
    attach(mount(ConfirmDialog, { attachTo: document.body }))
    const answer = askReason({ message: 'Dismiss?', reason: { default: 'pas nécessaire', recorded: 'not needed' } })
    await flushPromises()
    expect(dialog().querySelector('input')!.value).toBe('pas nécessaire')
    press('Enter')
    expect(await answer).toBe('not needed')
  })

  it('Escape cancels the dialog only, not the tutorial offer under it', async () => {
    attach(mount(TutorialOffer, { attachTo: document.body }))
    attach(mount(ConfirmDialog, { attachTo: document.body }))
    tutorial.markOffered.mockClear()
    const answer = confirmed({ message: 'Sure?' })
    await flushPromises()
    press('Escape')
    expect(await answer).toBe(false)
    expect(tutorial.markOffered).not.toHaveBeenCalled()
    // With no dialog open, the same Escape answers the offer ("later").
    press('Escape')
    expect(tutorial.markOffered).toHaveBeenCalledOnce()
  })

  it('Escape cancels the dialog only, not the panel opened from the settings under it', async () => {
    setPanel('settings', true)
    setPanel('caps', true)
    attach(mount(SettingsPanel, { attachTo: document.body }))
    await flushPromises()
    opener()
    attach(mount(ConfirmDialog, { attachTo: document.body }))
    const answer = confirmed({ message: 'Sure?' })
    await flushPromises()
    press('Escape')
    expect(await answer).toBe(false)
    expect(panels.caps).toBe(true)
    // With no dialog open, the same Escape closes the panel above the settings.
    press('Escape')
    expect(panels.caps).toBe(false)
  })
})
