import { describe, expect, it } from 'vitest'
import { keyPerContent } from './idempotency'

describe('one Idempotency-Key per content', () => {
  const keys = () => {
    let n = 0
    return keyPerContent(() => `k${++n}`)
  }

  it('reuses the key for the same content, and takes a new one once the content changes', () => {
    const k = keys()
    expect(k.keyFor({ title: 'Ship it' })).toBe('k1')
    expect(k.keyFor({ title: 'Ship it' }), 'a retry after a lost answer').toBe('k1')
    expect(k.keyFor({ title: 'Ship it now' }), 'an edited form').toBe('k2')
    expect(k.keyFor({ title: 'Ship it' }), 'back to the first content: a new key, not the reused one').toBe('k3')
  })

  it('forgets the key once the mission is created', () => {
    const k = keys()
    expect(k.keyFor({ title: 'Ship it' })).toBe('k1')
    k.done()
    expect(k.keyFor({ title: 'Ship it' })).toBe('k2')
  })
})
