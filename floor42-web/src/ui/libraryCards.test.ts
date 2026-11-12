import { describe, expect, it } from 'vitest'
import type { ProjectView } from '../api/types'
import { coverOf } from '../scene/cover'
import { spineColors } from '../state/library'
import { coverSvg } from './coverArt'
import { booksOf, sectionCounts, shortPath } from './libraryCards'

const project = (path: string, extra: Partial<ProjectView> = {}): ProjectView => ({
  path,
  missions: ['M-1'],
  lastTitle: 'Title',
  office: false,
  exists: true,
  git: null,
  lastMission: 'M-1',
  lastActivity: null,
  pinned: false,
  forgotten: false,
  ...extra,
})

describe('library cards', () => {
  const list = [project('/work/atlas', { pinned: true }), project('/work/harbour-api', { lastTitle: 'Fix the login' }), project('/old/kiln', { exists: false })]

  it('shortens a long path from the middle and leaves a short one alone', () => {
    expect(shortPath('/work/atlas')).toBe('/work/atlas')
    const long = shortPath('C:\\Users\\director\\work\\clients\\acme\\services\\billing-api', 30)
    expect(long.length).toBeLessThanOrEqual(30)
    expect(long).toContain('…')
    expect(long.endsWith('billing-api')).toBe(true)
  })

  it('filters by section and by every word of the search, accents and case ignored', () => {
    expect(booksOf(list, 'pinned', '').map((p) => p.path)).toEqual(['/work/atlas'])
    expect(booksOf(list, 'gone', '').map((p) => p.path)).toEqual(['/old/kiln'])
    expect(booksOf(list, 'all', 'LOGIN harbour').map((p) => p.path)).toEqual(['/work/harbour-api'])
    expect(booksOf(list, 'all', 'nothing here')).toEqual([])
    expect(sectionCounts(list)).toEqual({ all: 3, pinned: 1, gone: 1 })
  })

  it('draws the cover from numbers only, the same for the same project', () => {
    const svg = coverSvg(coverOf('/work/atlas', '1990s'))
    expect(svg).toBe(coverSvg(coverOf('/work/atlas', '1990s')))
    expect(svg.startsWith('<svg')).toBe(true)
    expect(svg).not.toContain('atlas')
    expect(svg).toMatch(/fill="#[0-9a-f]{6}"/)
  })

  it('paints each spine of the bookcase with the colour of the same cover', () => {
    expect(spineColors(list, '2000s')).toEqual(list.map((p) => coverOf(p.path, '2000s').spine))
  })
})
