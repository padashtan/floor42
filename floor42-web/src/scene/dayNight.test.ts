import { describe, expect, it } from 'vitest'
import { eased, next, sceneHour } from './dayNight'

describe('day and night choice', () => {
  it('sceneHour: auto follows the clock, minutes as a fraction', () => {
    expect(sceneHour('auto', new Date(2026, 10, 13, 21, 30))).toBe(21.5)
  })

  it('sceneHour: day is 12 and night is 22 whatever the clock', () => {
    for (const h of [3, 12, 21]) {
      const clock = new Date(2026, 10, 13, h, 10)
      expect(sceneHour('day', clock)).toBe(12)
      expect(sceneHour('night', clock)).toBe(22)
    }
  })

  it('next cycles auto, day, night, auto', () => {
    expect(next('auto')).toBe('day')
    expect(next('day')).toBe('night')
    expect(next('night')).toBe('auto')
  })

  it('eased: reduced motion jumps to the target', () => {
    expect(eased(12, 22, 0.1, true)).toBe(22)
  })

  it('eased: goes the short way round midnight', () => {
    expect(eased(23, 1, 0.5, false)).toBeCloseTo(0)
    expect(eased(23, 1, 1, false)).toBe(1)
    expect(eased(12, 22, 0.5, false)).toBeCloseTo(17)
  })
})
