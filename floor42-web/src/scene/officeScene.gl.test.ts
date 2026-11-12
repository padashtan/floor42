import * as THREE from 'three'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { City } from './city'
import { OfficeScene } from './OfficeScene'
import { applyTheme } from './palette'
import { ResourceCache } from './resources'
import { fakeBrowser, QUIET } from './testing/fakeBrowser'
import type { GlCounts } from './testing/fakeGl'

const AGENTS = [
  { id: 'dev-1', name: 'Ada', status: 'WORKING' as const },
  { id: 'dev-2', name: 'Bob', status: 'AVAILABLE' as const },
  { id: 'dev-3', name: 'Cy', status: 'WAITING_FOR_DIRECTOR' as const },
]

/**
 * What three.js keeps for itself on a context: the renderer's empty default textures and framebuffers, and the program
 * of its private shadow depth material. `renderer.dispose()` deletes none of them; the context's loss frees them, and
 * OfficeScene loses its context at once on dispose. A literal, measured by the last test with three.js alone: none of
 * the code under test goes into what a test expects.
 */
const RENDERER_OWN: GlCounts = { buffers: 0, textures: 4, framebuffers: 3, renderbuffers: 0, programs: 1, shaders: 0, vertexArrays: 0 }

/** The parts of an OfficeScene the cache test reaches into: its renderer and the city's resource cache. */
interface Inside {
  pipeline: { renderer: THREE.WebGLRenderer }
  city: { cache: ResourceCache }
}

describe('a change of decade gives the GPU back everything the replaced scene took', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

  it('runs the office of every decade on a counting WebGL context, then frees all but the renderer’s own', { timeout: 60_000 }, () => {
    const browser = fakeBrowser()
    for (const theme of ['1980s', '1990s', '2000s', '2020s', '1980s'] as const) {
      const scene = new OfficeScene(browser.canvas(), undefined, QUIET, theme)
      // Figures (and their emblem textures), the selection ring, a gesture's effects, a mission's end, a zoom out.
      scene.setAgents(AGENTS)
      scene.select({ kind: 'agent', id: 'dev-1' })
      scene.celebrate(['dev-1', 'dev-2'])
      scene.view = { ...scene.view, height: 50 }
      browser.frames(45)
      // Statuses change: emblems swap textures, the one shown before leaves the scene graph.
      scene.setAgents(AGENTS.map((a) => ({ ...a, status: 'PAUSED' as const })))
      browser.frames(45)
      const drawn = browser.contexts.at(-1)!.counts
      expect(drawn.buffers, `${theme}: the scene uploaded its geometries`).toBeGreaterThan(1000)
      scene.dispose()
    }
    expect(browser.contexts.map((c) => c.counts)).toEqual(browser.contexts.map(() => RENDERER_OWN))
  })

  it('frees what the city’s cache made even once no mesh of the scene graph holds it', { timeout: 60_000 }, () => {
    const browser = fakeBrowser()
    const scene = new OfficeScene(browser.canvas(), undefined, QUIET, '2000s')
    browser.frames(2)
    const inside = scene as unknown as Inside
    // A texture the city's cache made and the GPU holds, on no mesh: only the city's own disposal can reach it.
    const picture = new THREE.DataTexture(new Uint8Array(4), 1, 1)
    picture.needsUpdate = true
    inside.city.cache.material('off-graph', () => new THREE.MeshBasicMaterial({ map: picture }))
    inside.pipeline.renderer.initTexture(picture)
    const uploaded = browser.contexts[0].counts.textures
    scene.dispose()
    expect(uploaded, 'the picture was uploaded').toBeGreaterThan(RENDERER_OWN.textures)
    expect(browser.contexts[0].counts).toEqual(RENDERER_OWN)
  })

  it('the city alone frees all it uploaded: its graph and its cache', () => {
    const browser = fakeBrowser()
    const renderer = new THREE.WebGLRenderer({ canvas: browser.canvas(), antialias: false })
    const camera = new THREE.OrthographicCamera(-80, 80, 80, -80, 0.1, 400).translateY(120).translateZ(120)
    camera.lookAt(0, 0, 0)
    renderer.render(new THREE.Scene(), camera)
    const empty = { ...browser.contexts[0].counts }
    const city = new City(10, 10, 20, 20)
    city.update(1, 0.1, 21)
    renderer.render(new THREE.Scene().add(city.root), camera)
    expect(browser.contexts[0].counts.buffers, 'the city uploaded its geometries').toBeGreaterThan(100)
    city.dispose()
    renderer.dispose()
    expect(browser.contexts[0].counts).toEqual(empty)
  })

  it('a resource cache frees what it made, textures included, even once no mesh in the graph holds it', () => {
    const browser = fakeBrowser()
    const renderer = new THREE.WebGLRenderer({ canvas: browser.canvas(), antialias: false })
    const before = { ...browser.contexts[0].counts }
    const cache = new ResourceCache()
    const picture = new THREE.DataTexture(new Uint8Array(4), 1, 1)
    picture.needsUpdate = true
    const mesh = new THREE.Mesh(cache.box(1, 1, 1), cache.material('pictured', () => new THREE.MeshBasicMaterial({ map: picture })))
    const scene = new THREE.Scene().add(mesh)
    renderer.render(scene, new THREE.PerspectiveCamera().translateZ(3))
    expect(browser.contexts[0].counts.textures, 'the picture was uploaded').toBe(before.textures + 1)
    scene.remove(mesh)
    cache.dispose()
    renderer.dispose()
    const after = browser.contexts[0].counts
    expect({ buffers: after.buffers, textures: after.textures }).toEqual({ buffers: 0, textures: before.textures })
  })

  it('counts the renderer’s own allocations with three.js alone: the residue a lost context frees (D18)', () => {
    const browser = fakeBrowser()
    const renderer = new THREE.WebGLRenderer({ canvas: browser.canvas(), antialias: false })
    renderer.shadowMap.enabled = true
    const light = new THREE.DirectionalLight()
    light.castShadow = true
    // An unlit material: three binds no texture of its own to it, so freeing the box's geometry and material is all.
    const box = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial())
    box.castShadow = true
    renderer.render(new THREE.Scene().add(light, box), new THREE.PerspectiveCamera())
    box.geometry.dispose()
    box.material.dispose()
    // The light's shadow map: its render target, freed with the light.
    light.dispose()
    renderer.dispose()
    expect(browser.contexts[0].counts).toEqual(RENDERER_OWN)
  })
})
