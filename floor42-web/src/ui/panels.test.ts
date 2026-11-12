import { describe, expect, it } from 'vitest'
import { clamp, clampSize, MIN_SIZE, panelKey, sizedStyle } from './panels'

describe('panels', () => {
  it('names a panel by its own classes, whatever their order', () => {
    expect(panelKey(['panel', 'board-panel', 'framed'])).toBe('board-panel')
    expect(panelKey(['workshop', 'panel'])).toBe('workshop')
    // The classes the installer adds while resizing or dragging do not make it another panel.
    expect(panelKey(['panel', 'board-panel', 'sized', 'dragging'])).toBe('board-panel')
  })

  it('keeps the title bar on screen', () => {
    const screen = { width: 1000, height: 800 }
    expect(clamp({ left: -900, top: -50 }, 400, screen)).toEqual({ left: -340, top: 0 })
    expect(clamp({ left: 2000, top: 2000 }, 400, screen)).toEqual({ left: 940, top: 740 })
    expect(clamp({ left: 100, top: 100 }, 400, screen)).toEqual({ left: 100, top: 100 })
  })

  it('keeps a resized panel usable and within the screen', () => {
    const screen = { width: 1000, height: 800 }
    expect(clampSize({ width: 10, height: 10 }, screen)).toEqual(MIN_SIZE)
    expect(clampSize({ width: 5000, height: 5000 }, screen)).toEqual({ width: 984, height: 784 })
    expect(clampSize({ width: 640.4, height: 480.6 }, screen)).toEqual({ width: 640, height: 481 })
  })

  it('keeps a saved size within a narrower window: the cap stays, it is never lifted', () => {
    const style = sizedStyle({ width: 1400, height: 700 }, { width: 600, height: 800 })
    expect(style).toEqual({ width: '584px', height: '700px', maxWidth: 'calc(100vw - 16px)', maxHeight: 'calc(100vh - 16px)' })
  })
})
