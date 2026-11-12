import { describe, expect, it } from 'vitest'
import { sameName } from './names'

describe('two agent names', () => {
  it('are one name whatever the case, the width, a zero-width character or the spaces', () => {
    for (const twin of ['dev', ' DEV ', 'D\u200Bev', '\uFF24\uFF45\uFF56']) expect(sameName(twin)).toBe('dev')
    expect(sameName('Mary  Ann')).toBe('mary ann')
    for (const twin of ['D\u200Eev', 'De\u034Fv', 'Dev\uFE0F', 'Mary\u00A0Ann', 'Mary\u3000Ann', 'Mary\u2009Ann']) {
      expect(sameName(twin)).toBe(twin.startsWith('Mary') ? 'mary ann' : 'dev')
    }
    expect(sameName('Devon')).not.toBe('dev')
  })
})
