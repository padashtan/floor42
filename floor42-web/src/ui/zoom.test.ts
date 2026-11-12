import { describe, expect, it } from 'vitest'
import { nextZoom, STEPS } from './zoom'

describe('image zoom steps (ADR-0051)', () => {
  it('starts at actual size from fit (out stays fit), then walks the steps and holds at the ends', () => {
    expect(nextZoom('fit', 1)).toBe(1)
    expect(nextZoom('fit', -1)).toBe('fit')
    expect(nextZoom(1, 1)).toBe(1.5)
    expect(nextZoom(1, -1)).toBe(0.75)
    expect(nextZoom(STEPS[STEPS.length - 1], 1)).toBe(STEPS[STEPS.length - 1])
    expect(nextZoom(STEPS[0], -1)).toBe(STEPS[0])
  })
})
