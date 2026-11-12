import * as THREE from 'three'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { applyTheme } from './palette'
import { ResourceCache, resourcesOf } from './resources'
import { fakeDocument } from './testing/sceneDigest'

// What a whole scene gives back to the GPU is counted on a fake WebGL context: officeScene.gl.test.ts.
describe('the resources of a scene graph', () => {
  beforeEach(() => vi.stubGlobal('document', fakeDocument().document))
  afterEach(() => {
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

  it('finds geometries, materials and the textures they hold, once each', () => {
    const texture = new THREE.Texture()
    const material = new THREE.MeshBasicMaterial({ map: texture })
    const geometry = new THREE.BoxGeometry()
    const root = new THREE.Group().add(new THREE.Mesh(geometry, material), new THREE.Mesh(geometry, material))
    expect([...resourcesOf(root)]).toEqual([geometry, material, texture])
  })

  it('a cache makes a geometry or a material once per key, and frees them with their textures', () => {
    const cache = new ResourceCache()
    const texture = new THREE.Texture()
    const m = cache.material('m', () => new THREE.MeshBasicMaterial({ map: texture }))
    expect(cache.material('m', () => new THREE.MeshBasicMaterial())).toBe(m)
    expect(cache.box(1, 2, 3)).toBe(cache.box(1, 2, 3))
    const freed: string[] = []
    texture.addEventListener('dispose', () => freed.push('texture'))
    m.addEventListener('dispose', () => freed.push('material'))
    cache.dispose()
    expect(freed).toEqual(['texture', 'material'])
  })
})
