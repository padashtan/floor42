import { describe, expect, it } from 'vitest'
import { filterSections, moveTo, normalize } from './sectioned'

describe('sectioned panel logic', () => {
  it('normalizes accents, case and spaces', () => {
    expect(normalize('  Réseau   COUPÉ ')).toBe('reseau coupe')
  })
  it('keeps the sections with a match, and only the matching items', () => {
    const sections = [
      { id: 'a', items: ['red apple', 'green pear'] },
      { id: 'b', items: ['blue plum'] },
    ]
    expect(filterSections(sections, 'APPLE red', (x) => x)).toEqual([{ id: 'a', items: ['red apple'] }])
    expect(filterSections(sections, '  ', (x) => x)).toHaveLength(2)
    expect(filterSections(sections, 'kiwi', (x) => x)).toEqual([])
  })
  it('moves with the arrows, wrapping, and with Home and End', () => {
    const ids = ['a', 'b', 'c']
    expect(moveTo(ids, 'a', 'ArrowDown')).toBe('b')
    expect(moveTo(ids, 'a', 'ArrowUp')).toBe('c')
    expect(moveTo(ids, 'c', 'ArrowRight')).toBe('a')
    expect(moveTo(ids, 'b', 'Home')).toBe('a')
    expect(moveTo(ids, 'b', 'End')).toBe('c')
    expect(moveTo(ids, 'b', 'x')).toBeNull()
  })
})
