/**
 * Floating panels the director can move, resize and stack: drag a panel by its title bar, resize it from its bottom
 * right corner, click a panel to bring it to the front, double click its title to put it back where and as it opens
 * by default. Each panel keeps its place and size in this browser (a convenience only: nothing reaches the server).
 * Works on every panel of the HUD with a title bar, without touching the components.
 */
import { addPin, pinClicked } from './pinButton'

export interface Place {
  left: number
  top: number
}

export interface Size {
  width: number
  height: number
}

const STORAGE = 'floor42.panels'
const SIZES = 'floor42.panel-sizes'
/** No smaller than this: the title bar and a few lines stay usable. */
export const MIN_SIZE: Size = { width: 280, height: 160 }
/** Panels stack from here; menus, tooltips and toasts stay above (z-index 40 and more). */
const BASE_Z = 21
const MAX_Z = 39
/** Always leave this much of a panel on screen, so it can be caught again. */
const KEEP_VISIBLE = 60

/** A panel's identity across openings: its own classes, without the generic ones. */
export function panelKey(classes: Iterable<string>): string {
  return [...classes]
    .filter((c) => c !== 'panel' && c !== 'framed' && c !== 'sized' && c !== 'dragging')
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .join('.')
}

/** Keeps the title bar reachable: never above the screen, never further than KEEP_VISIBLE pixels off a side. */
export function clamp(p: Place, width: number, viewport: { width: number; height: number }): Place {
  return {
    left: Math.min(Math.max(p.left, KEEP_VISIBLE - width), viewport.width - KEEP_VISIBLE),
    top: Math.min(Math.max(p.top, 0), viewport.height - KEEP_VISIBLE),
  }
}

/** The margin a resized panel leaves to the window's edges, as the stylesheet's own cap does (100vw - 16px). */
export const EDGE = 16

/** Never smaller than {@link MIN_SIZE}, never larger than the screen less {@link EDGE}. */
export function clampSize(s: Size, viewport: { width: number; height: number }): Size {
  const fit = (value: number, min: number, room: number) => Math.round(Math.min(Math.max(value, min), Math.max(min, room - EDGE)))
  return { width: fit(s.width, MIN_SIZE.width, viewport.width), height: fit(s.height, MIN_SIZE.height, viewport.height) }
}

/**
 * The inline style of a panel the director resized: its size within the screen, and a cap that still holds when the
 * window gets smaller afterwards (a size saved on a wide screen never makes a panel wider than a narrow one).
 */
export function sizedStyle(s: Size, viewport: { width: number; height: number }): Record<'width' | 'height' | 'maxWidth' | 'maxHeight', string> {
  const c = clampSize(s, viewport)
  return { width: `${c.width}px`, height: `${c.height}px`, maxWidth: `calc(100vw - ${EDGE}px)`, maxHeight: `calc(100vh - ${EDGE}px)` }
}

function load<T>(key: string): Record<string, T> {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as Record<string, T>) : {}
  } catch {
    return {}
  }
}

/**
 * Records one panel's value (undefined forgets it), in memory and in storage. Storage is read again first: another tab
 * of the office may have saved its own panels since this one loaded.
 */
function remember<T>(key: string, memory: Record<string, T>, panel: string, value: T | undefined) {
  const saved = load<T>(key)
  if (value === undefined) {
    delete memory[panel]
    delete saved[panel]
  } else {
    memory[panel] = value
    saved[panel] = value
  }
  try {
    localStorage.setItem(key, JSON.stringify(saved))
  } catch {
    // Kept for this page only.
  }
}

function movable(el: Element): el is HTMLElement {
  return (
    el instanceof HTMLElement &&
    el.classList.contains('panel') &&
    // The context menu opens where the click was and stays above everything.
    !el.classList.contains('menu') &&
    !!el.querySelector(':scope > .title')
  )
}

function place(el: HTMLElement, p: Place) {
  const c = clamp(p, el.offsetWidth, { width: window.innerWidth, height: window.innerHeight })
  el.style.left = `${c.left}px`
  el.style.top = `${c.top}px`
  el.style.right = 'auto'
  el.style.bottom = 'auto'
  el.style.transform = 'none'
}

function unplace(el: HTMLElement) {
  for (const p of ['left', 'top', 'right', 'bottom', 'transform']) el.style.removeProperty(p)
}

/** A size set by the director wins over the panel's own limits: its content fills it and scrolls inside. */
function resize(el: HTMLElement, s: Size) {
  Object.assign(el.style, sizedStyle(s, { width: window.innerWidth, height: window.innerHeight }))
  el.classList.add('sized')
}

function unsize(el: HTMLElement) {
  for (const p of ['width', 'height', 'max-width', 'max-height']) el.style.removeProperty(p)
  el.classList.remove('sized')
}

function addHandle(el: HTMLElement) {
  if (el.querySelector(':scope > .resize-handle')) return
  const handle = document.createElement('div')
  handle.className = 'resize-handle'
  handle.title = 'Resize'
  el.appendChild(handle)
}

export function installPanels(hud: HTMLElement): () => void {
  const places = load<Place>(STORAGE)
  const sizes = load<Size>(SIZES)
  let stack: HTMLElement[] = []

  function raise(el: HTMLElement) {
    stack = stack.filter((x) => x.isConnected && x !== el)
    stack.push(el)
    // Renumbered each time: the order matters, not the numbers, and they never climb over the menus.
    stack.slice(-(MAX_Z - BASE_Z + 1)).forEach((x, i) => (x.style.zIndex = String(BASE_Z + i)))
  }

  function restore() {
    for (const el of Array.from(hud.children)) {
      if (!movable(el) || el.dataset.placed) continue
      el.dataset.placed = '1'
      addHandle(el)
      addPin(el)
      const size = sizes[panelKey(el.classList)]
      if (size) resize(el, size)
      const saved = places[panelKey(el.classList)]
      if (saved) place(el, saved)
      raise(el)
    }
  }

  function down(e: PointerEvent) {
    const panel = (e.target as Element).closest('.hud > .panel')
    if (!(panel instanceof HTMLElement) || !movable(panel)) return
    raise(panel)
    const handle = (e.target as Element).closest<HTMLElement>('.resize-handle')
    if (e.button === 0 && handle && handle.parentElement === panel) {
      startResize(panel, handle, e)
      return
    }
    const title = (e.target as Element).closest<HTMLElement>('.title')
    if (e.button !== 0 || !title || title.parentElement !== panel) return
    if ((e.target as Element).closest('button, a, input, select, textarea')) return
    const rect = panel.getBoundingClientRect()
    const dx = e.clientX - rect.left
    const dy = e.clientY - rect.top
    title.setPointerCapture(e.pointerId)
    panel.classList.add('dragging')
    const move = (m: PointerEvent) => place(panel, { left: m.clientX - dx, top: m.clientY - dy })
    const up = () => {
      title.removeEventListener('pointermove', move)
      title.removeEventListener('pointerup', up)
      title.removeEventListener('pointercancel', up)
      panel.classList.remove('dragging')
      remember(STORAGE, places, panelKey(panel.classList), {
        left: parseFloat(panel.style.left) || rect.left,
        top: parseFloat(panel.style.top) || rect.top,
      })
    }
    title.addEventListener('pointermove', move)
    title.addEventListener('pointerup', up)
    title.addEventListener('pointercancel', up)
    e.preventDefault()
  }

  function startResize(panel: HTMLElement, handle: HTMLElement, e: PointerEvent) {
    const rect = panel.getBoundingClientRect()
    // A panel placed by its stylesheet (right, bottom, a transform) is pinned where it is, so it grows from there.
    place(panel, { left: rect.left, top: rect.top })
    handle.setPointerCapture(e.pointerId)
    panel.classList.add('dragging')
    const move = (m: PointerEvent) =>
      resize(panel, { width: rect.width + m.clientX - e.clientX, height: rect.height + m.clientY - e.clientY })
    const up = () => {
      handle.removeEventListener('pointermove', move)
      handle.removeEventListener('pointerup', up)
      handle.removeEventListener('pointercancel', up)
      panel.classList.remove('dragging')
      const key = panelKey(panel.classList)
      remember(SIZES, sizes, key, { width: panel.offsetWidth, height: panel.offsetHeight })
      remember(STORAGE, places, key, {
        left: parseFloat(panel.style.left) || rect.left,
        top: parseFloat(panel.style.top) || rect.top,
      })
    }
    handle.addEventListener('pointermove', move)
    handle.addEventListener('pointerup', up)
    handle.addEventListener('pointercancel', up)
    e.preventDefault()
  }

  function reset(e: MouseEvent) {
    const title = (e.target as Element).closest('.title')
    const panel = title?.parentElement
    if (!panel || !movable(panel) || panel.parentElement !== hud) return
    if ((e.target as Element).closest('button, a, input, select, textarea')) return
    unplace(panel)
    unsize(panel)
    const key = panelKey(panel.classList)
    remember(STORAGE, places, key, undefined)
    remember(SIZES, sizes, key, undefined)
  }

  const observer = new MutationObserver(restore)
  observer.observe(hud, { childList: true })
  hud.addEventListener('pointerdown', down, true)
  hud.addEventListener('dblclick', reset)
  hud.addEventListener('click', pinClicked)
  restore()
  return () => {
    observer.disconnect()
    hud.removeEventListener('pointerdown', down, true)
    hud.removeEventListener('dblclick', reset)
    hud.removeEventListener('click', pinClicked)
  }
}
