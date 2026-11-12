import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import { coverOf } from './cover'
import { applyTheme, THEME_IDS } from './palette'
import { Bookcase, BOOKCASE, MAX_BOOKS } from './props'
import { BOOKCASE_AT, FURNITURE } from './floorPlan'
import { findRoot } from './picking'

describe('the cover of a project', () => {
  it('is the same every time for the same path and decade', () => {
    expect(coverOf('C:\\work\\atlas', '1990s')).toEqual(coverOf('C:\\work\\atlas', '1990s'))
  })

  it('differs between projects and takes the decade s cloth', () => {
    const covers = ['atlas', 'harbour', 'notes', 'kiln', 'orchard', 'lantern'].map((n) => coverOf(`/work/${n}`, '2020s'))
    expect(new Set(covers.map((c) => c.base)).size).toBeGreaterThan(2)
    expect(new Set(covers.map((c) => `${c.glyph}:${c.band}`)).size).toBeGreaterThan(4)
    const a = coverOf('/work/atlas', '1980s')
    const b = coverOf('/work/atlas', '2020s')
    expect(a.base).not.toBe(b.base)
  })

  it('keeps every colour a valid 24 bit value and a non empty glyph', () => {
    for (const era of THEME_IDS) {
      for (const key of ['a', 'bb', '/x/y/z', 'C:\\Users\\me\\repo']) {
        const c = coverOf(key, era)
        for (const v of [c.base, c.accent, c.ink, c.spine]) {
          expect(v).toBeGreaterThanOrEqual(0)
          expect(v).toBeLessThanOrEqual(0xffffff)
        }
        expect(c.glyph).toBeGreaterThan(0)
        expect(c.accent).not.toBe(c.ink)
      }
    }
  })
})

describe('the bookcase', () => {
  const books = (n: number) => Array.from({ length: n }, (_, i) => coverOf(`/p/${i}`, '1990s').spine)

  it('carries the library target, one spine per project, in the project colours', () => {
    applyTheme('1990s')
    const shelf = new Bookcase()
    const before = shelf.root.children.length
    shelf.setBooks(books(5))
    expect(shelf.root.userData.target).toEqual({ kind: 'library', id: 'bookcase' })
    expect(findRoot(shelf.root, { kind: 'library', id: 'bookcase' })).toBe(shelf.root)
    expect(shelf.root.children.length).toBe(before)
    const spines = shelf.root.children.at(-1)!.children as THREE.Mesh[]
    expect(spines).toHaveLength(5)
    expect(spines.map((m) => (m.material as THREE.MeshStandardMaterial).color.getHex())).toEqual(books(5))
  })

  it('replaces its books on each call and never shows more than it can hold', () => {
    const shelf = new Bookcase()
    shelf.setBooks(books(3))
    shelf.setBooks(books(MAX_BOOKS + 10))
    expect(shelf.root.children.at(-1)!.children).toHaveLength(MAX_BOOKS)
    shelf.setBooks([])
    expect(shelf.root.children.at(-1)!.children).toHaveLength(0)
  })

  it('disposes the geometry and the material of every book it takes off the shelf', () => {
    const shelf = new Bookcase()
    shelf.setBooks(books(3))
    const gone = (shelf.root.children.at(-1)!.children as THREE.Mesh[]).map((m) => ({ geometry: m.geometry, material: m.material as THREE.Material }))
    const disposed: string[] = []
    for (const [i, g] of gone.entries()) {
      g.geometry.addEventListener('dispose', () => disposed.push(`geometry ${i}`))
      g.material.addEventListener('dispose', () => disposed.push(`material ${i}`))
    }
    shelf.setBooks(books(1))
    expect(disposed.sort()).toEqual(['geometry 0', 'geometry 1', 'geometry 2', 'material 0', 'material 1', 'material 2'])
  })

  it('keeps its books inside its own footprint', () => {
    const shelf = new Bookcase()
    shelf.setBooks(books(MAX_BOOKS))
    const box = new THREE.Box3().setFromObject(shelf.root)
    expect(box.max.x - box.min.x).toBeLessThanOrEqual(BOOKCASE.width + 0.03)
    expect(box.max.z - box.min.z).toBeLessThanOrEqual(BOOKCASE.depth + 0.03)
  })

  it('has a footprint on the floor plan, against the west wall', () => {
    const mine = FURNITURE.find((r) => r.x0 <= BOOKCASE_AT.x && r.x1 >= BOOKCASE_AT.x && r.z0 <= BOOKCASE_AT.z && r.z1 >= BOOKCASE_AT.z)
    expect(mine).toBeDefined()
    expect(mine!.x0).toBeLessThan(0.2)
  })
})
