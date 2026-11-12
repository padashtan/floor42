import { describe, expect, it } from 'vitest'
import { layout, type Segment } from './gitGraph'

const c = (hash: string, ...parents: string[]) => ({ hash, parents })
const seg = (fromRow: number, fromLane: number, toRow: number, toLane: number, color: number): Segment => ({
  fromRow,
  fromLane,
  toRow,
  toLane,
  color,
})

describe('git graph lanes', () => {
  it('keeps a straight history on one lane', () => {
    const g = layout([c('c', 'b'), c('b', 'a'), c('a')], ['c'])
    expect(g.lanes).toEqual([0, 0, 0])
    expect(g.segments).toEqual([seg(0, 0, 1, 0, 0), seg(1, 0, 2, 0, 0)])
    expect(g.width).toBe(1)
  })

  it('puts the director’s branch on lane 0 even when an agent’s branch is ahead of it', () => {
    // The agent branched from the director's tip and committed once.
    const g = layout([c('agent', 'main'), c('main', 'base'), c('base')], ['main', 'agent'])
    expect(g.lanes).toEqual([1, 0, 0])
    // The agent's line joins the director's lane at the commit it started from, in the agent's colour.
    expect(g.segments).toContainEqual(seg(0, 1, 1, 0, 1))
    expect(g.width).toBe(2)
  })

  it('draws a merge as a fork into its own lane that joins back', () => {
    // m merges b into a; b was made on a side branch from a.
    const g = layout([c('m', 'a', 'b'), c('b', 'a'), c('a')], ['m'])
    expect(g.lanes).toEqual([0, 1, 0])
    expect(g.segments).toHaveLength(3)
    // The merge's second parent opens lane 1, in lane 1's colour.
    expect(g.segments).toContainEqual(seg(0, 0, 1, 1, 1))
    // b's line joins lane 0 at a, in its own colour.
    expect(g.segments).toContainEqual(seg(1, 1, 2, 0, 1))
    // First parent, straight down.
    expect(g.segments).toContainEqual(seg(0, 0, 2, 0, 0))
  })

  it('keeps two agents on lanes of their own, in the order of the heads', () => {
    const g = layout(
      [c('x2', 'x1'), c('y1', 'base'), c('x1', 'base'), c('main', 'base'), c('base')],
      ['main', 'x2', 'y1'],
    )
    expect(g.lanes).toEqual([1, 2, 1, 0, 0])
    expect(g.width).toBe(3)
    // Lane 1 runs from x2 down to x1, then joins lane 0 at base.
    expect(g.segments).toContainEqual(seg(0, 1, 2, 1, 1))
  })

  it('runs a line to a parent that is not shown off the bottom', () => {
    const g = layout([c('b', 'a')], ['b'])
    expect(g.lanes).toEqual([0])
    expect(g.segments).toEqual([seg(0, 0, 1, 0, 0)])
  })

  it('frees a lane once its line ends, for the next branch', () => {
    // Two successive merges of side branches: both reuse lane 1.
    const g = layout(
      [c('m2', 'm1', 's2'), c('s2', 'm1'), c('m1', 'a', 's1'), c('s1', 'a'), c('a')],
      ['m2'],
    )
    expect(g.lanes).toEqual([0, 1, 0, 1, 0])
    expect(g.width).toBe(2)
  })

  it('ignores a head that is not in the graph and shows nothing for no commit', () => {
    expect(layout([c('a')], ['gone', 'a']).lanes).toEqual([0])
    expect(layout([], ['a'])).toEqual({ lanes: [], segments: [], width: 0 })
  })
})
