import { describe, expect, it } from 'vitest'
import { namedPaths, suggestTitle } from './missionForm'

describe('suggestTitle', () => {
  it('takes the first non-blank line, trimmed', () => {
    expect(suggestTitle('\n  Add the login page  \nmore')).toBe('Add the login page')
  })
  it('cuts a long line at a word within 60 characters', () => {
    const t = suggestTitle('word '.repeat(30))
    expect(t.length).toBeLessThanOrEqual(60)
    expect(t.endsWith('word')).toBe(true)
  })
  it('is empty for a blank objective', () => {
    expect(suggestTitle('  \n ')).toBe('')
  })
})

describe('namedPaths', () => {
  it('finds file paths once each and ignores plain words and urls without extension', () => {
    expect(namedPaths('Edit floor42-web/src/scene/x.ts then floor42-web/src/scene/x.ts and a/b.md, not just words')).toEqual([
      'floor42-web/src/scene/x.ts',
      'a/b.md',
    ])
  })
})
