import { describe, expect, it } from 'vitest'
import { why } from './why'

describe('why', () => {
  it('gives the first reason that holds', () => {
    expect(why([false, 'a'], [true, 'b'], [true, 'c'])).toBe('b')
  })

  it('gives nothing when the control is enabled', () => {
    expect(why([false, 'a'])).toBeUndefined()
  })
})
