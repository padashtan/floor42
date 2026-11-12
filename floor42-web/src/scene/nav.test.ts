import { describe, expect, it } from 'vitest'
import { crosses, NavGrid, type Point } from './nav'

// A 10x6 floor split by a wall at x = 5, with a door between z = 4 and z = 5.
const wall = [
  { x0: 4.9, z0: 0, x1: 5.1, z1: 4 },
  { x0: 4.9, z0: 5, x1: 5.1, z1: 6 },
]

function crossesWall(path: Point[], from: Point): boolean {
  let prev = from
  for (const p of path) {
    if ((prev.x - 5) * (p.x - 5) < 0) {
      const zAtWall = prev.z + ((p.z - prev.z) * (5 - prev.x)) / (p.x - prev.x)
      if (zAtWall < 4 || zAtWall > 5) return true
    }
    prev = p
  }
  return false
}

describe('NavGrid', () => {
  it('walks through the door rather than through the wall', () => {
    const grid = new NavGrid(10, 6, wall)
    const from = { x: 2, z: 1 }
    const to = { x: 8, z: 1 }

    const path = grid.path(from, to)

    expect(path.at(-1)).toEqual(to)
    expect(crossesWall(path, from)).toBe(false)
    expect(path.some((p) => p.z > 3.5 && p.z < 5.5)).toBe(true)
  })

  it('goes straight when nothing is in the way', () => {
    const grid = new NavGrid(10, 6, [])
    expect(grid.path({ x: 1, z: 1 }, { x: 4, z: 4 })).toEqual([{ x: 4, z: 4 }])
  })

  it('can end on an occupied spot such as a chair, but not walk across obstacles', () => {
    const desk = { x0: 3, z0: 2, x1: 4, z1: 3 }
    const grid = new NavGrid(10, 6, [desk])
    const path = grid.path({ x: 1, z: 2.5 }, { x: 3.5, z: 2.5 })
    expect(path.at(-1)).toEqual({ x: 3.5, z: 2.5 })
  })

  it('returns no path to an enclosed place', () => {
    const box = [
      { x0: 6, z0: 2, x1: 9, z1: 2.2 },
      { x0: 6, z0: 4.8, x1: 9, z1: 5 },
      { x0: 6, z0: 2, x1: 6.2, z1: 5 },
      { x0: 8.8, z0: 2, x1: 9, z1: 5 },
    ]
    const grid = new NavGrid(10, 6, box)
    expect(grid.path({ x: 1, z: 1 }, { x: 7.5, z: 3.5 })).toEqual([])
  })

  it('a prop placed after the grid was built is walked around', () => {
    const grid = new NavGrid(10, 6, [])
    const from = { x: 1, z: 3 }
    const to = { x: 9, z: 3 }
    const box = { x0: 4.5, z0: 2.5, x1: 5.5, z1: 3.5 }
    expect(crosses(from, grid.path(from, to), box, 0.15)).toBe(true)

    grid.addObstacle(box)

    const path = grid.path(from, to)
    expect(path.at(-1)).toEqual(to)
    expect(crosses(from, path, box, 0.15)).toBe(false)
  })

  it('crosses no obstacle near an end that stands on free floor', () => {
    // The start is next to a crate, not on it: the walk goes round, not through its corner.
    const crate = { x0: 2, z0: 1, x1: 3, z1: 4 }
    const grid = new NavGrid(10, 6, [crate])
    const from = { x: 1.5, z: 4.5 }
    expect(grid.isBlocked(from)).toBe(false)
    const path = grid.path(from, { x: 3.6, z: 1.5 })
    expect(path.length).toBeGreaterThan(0)
    expect(crosses(from, path, crate, 0.15)).toBe(false)
  })

  it('moves a prop that would close the only door to a spot that keeps every key reachable', () => {
    const grid = new NavGrid(10, 6, wall)
    const hub = { x: 2, z: 3 }
    const keys = [{ x: 8, z: 3 }]
    const inDoorway = { x0: 4.7, z0: 4.1, x1: 5.3, z1: 4.9 }

    const spot = grid.findSpot(inDoorway, hub, keys)

    expect(spot).not.toBeNull()
    expect(spot).not.toEqual(inDoorway)
    grid.addObstacle(spot!)
    expect(grid.path(hub, keys[0]).length).toBeGreaterThan(0)
  })

  it('keeps a prop where it is when it blocks nothing', () => {
    const grid = new NavGrid(10, 6, wall)
    const corner = { x0: 0.2, z0: 0.2, x1: 0.8, z1: 0.8 }
    expect(grid.findSpot(corner, { x: 2, z: 3 }, [{ x: 8, z: 3 }])).toEqual(corner)
  })
})
