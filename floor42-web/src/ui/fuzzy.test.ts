import { describe, expect, it } from 'vitest'
import { fold, fuzzyScore, rank } from './fuzzy'

describe('fuzzy search', () => {
  it('ignores case and accents', () => {
    expect(fold('Équipe Système')).toBe('equipe systeme')
    expect(fuzzyScore('equipe', 'Équipe')).not.toBeNull()
    expect(fuzzyScore('ÉQUIPE', 'equipe')).not.toBeNull()
  })

  it('finds letters in order with gaps, never out of order', () => {
    expect(fuzzyScore('wksp', 'Workshop')).not.toBeNull()
    expect(fuzzyScore('pskw', 'Workshop')).toBeNull()
    expect(fuzzyScore('xyz', 'Workshop')).toBeNull()
  })

  it('needs every word of the query', () => {
    expect(fuzzyScore('api con', 'API console')).not.toBeNull()
    expect(fuzzyScore('api board', 'API console')).toBeNull()
  })

  it('scores the start of a word and a whole substring higher', () => {
    expect(fuzzyScore('bo', 'Board')!).toBeGreaterThan(fuzzyScore('bo', 'Big room')!)
    expect(fuzzyScore('set', 'Settings')!).toBeGreaterThan(fuzzyScore('set', 'Sandbox readiness test')!)
  })

  it('ranks best first, keeps the given order on ties, and returns everything for an empty query', () => {
    const items = ['Sandbox', 'Skills', 'Settings']
    const name = (s: string): [string] => [s]
    expect(rank(items, '', name)).toEqual(items)
    expect(rank(items, 's', name)).toEqual(items)
    expect(rank(items, 'set', name)[0]).toBe('Settings')
    expect(rank(items, 'zzz', name)).toEqual([])
    expect(rank(items, 's', name, 2)).toHaveLength(2)
  })

  it('prefers a match on the name over one in the description', () => {
    const items = [
      { name: 'Caps', detail: 'Cost caps per session and mission' },
      { name: 'Mission M-7', detail: 'put on the board' },
    ]
    expect(rank(items, 'mission', (i) => [i.name, i.detail])[0].name).toBe('Mission M-7')
  })
})
