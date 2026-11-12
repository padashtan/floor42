import { t } from '../i18n'
import { isPinned, togglePinned } from '../state/pinned'
import { DOCKED } from './panelLayout'

/**
 * The pin button of a panel's frame, put next to its close cross (ui/panels.ts adds it to every panel with a cross):
 * a pinned panel stays open and live while others open and Escape leaves it alone (state/pinned.ts). Docked panels
 * are always on screen and have no cross: they get none.
 */
export function addPin(panel: HTMLElement): void {
  const id = panel.dataset.panel
  const title = panel.querySelector(':scope > .title')
  const close = title?.querySelector(':scope > button.close')
  if (!id || !title || !close || DOCKED.includes(id) || title.querySelector(':scope > button.pin')) return
  const pin = document.createElement('button')
  pin.type = 'button'
  pin.className = 'pin'
  look(pin, isPinned(id))
  title.insertBefore(pin, close)
}

function look(pin: HTMLElement, on: boolean): void {
  const label = t(on ? 'pin.unpin' : 'pin.pin')
  pin.textContent = on ? '◆' : '◇'
  pin.title = label
  pin.setAttribute('aria-label', label)
  pin.setAttribute('aria-pressed', String(on))
  pin.closest<HTMLElement>('.panel')?.toggleAttribute('data-pinned', on)
}

/** A click on a pin button: pins or unpins its panel; any other click does nothing. */
export function pinClicked(e: Event): void {
  const pin = (e.target as Element).closest<HTMLElement>('button.pin')
  const id = pin?.closest<HTMLElement>('.panel')?.dataset.panel
  if (pin && id) look(pin, togglePinned(id))
}
