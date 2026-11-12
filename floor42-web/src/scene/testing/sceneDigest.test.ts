import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import { digest } from './sceneDigest'

/** A small scene with what the office uses: a standard material, a shader material, a key light with its shadow, a point light, fog. */
function scene(): THREE.Scene {
  const s = new THREE.Scene()
  s.background = new THREE.Color(0x101010)
  s.fog = new THREE.Fog(0x101010, 85, 150)
  s.add(new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshStandardMaterial({ color: 0x808080, roughness: 0.9, metalness: 0 })))
  s.add(new THREE.Mesh(new THREE.PlaneGeometry(), new THREE.ShaderMaterial({ uniforms: { blur: { value: 0 } } })))
  const key = new THREE.DirectionalLight(0xffffff, 2.4)
  key.castShadow = true
  key.shadow.bias = -0.0015
  s.add(key, key.target)
  s.add(new THREE.PointLight(0xfff1d6, 4, 7, 1.4))
  return s
}

const mesh = (s: THREE.Scene, i: number) => s.children[i] as THREE.Mesh
const standard = (s: THREE.Scene) => mesh(s, 0).material as THREE.MeshStandardMaterial
const point = (s: THREE.Scene) => s.children.find((o) => o instanceof THREE.PointLight) as THREE.PointLight
const key = (s: THREE.Scene) => s.children.find((o) => o instanceof THREE.DirectionalLight) as THREE.DirectionalLight

/** Each change below alters what is drawn: the digest must change with it, or a snapshot would miss it. */
const MUTATIONS: Record<string, (s: THREE.Scene) => void> = {
  roughness: (s) => (standard(s).roughness = 0.8),
  metalness: (s) => (standard(s).metalness = 0.3),
  emissive: (s) => standard(s).emissive.setHex(0x330000),
  emissiveIntensity: (s) => (standard(s).emissiveIntensity = 0.5),
  'a normal map': (s) => (standard(s).normalMap = new THREE.Texture()),
  'an emissive map': (s) => (standard(s).emissiveMap = new THREE.Texture()),
  'a shader uniform': (s) => ((mesh(s, 1).material as THREE.ShaderMaterial).uniforms.blur.value = 1),
  'a point light distance': (s) => (point(s).distance = 8),
  'a point light decay': (s) => (point(s).decay = 2),
  'a shadow bias': (s) => (key(s).shadow.bias = -0.002),
  'a shadow map size': (s) => key(s).shadow.mapSize.set(1024, 1024),
  'a light target': (s) => key(s).target.position.set(1, 0, 0),
  'casting shadows': (s) => (point(s).castShadow = true),
  'the fog colour': (s) => (s.fog as THREE.Fog).color.setHex(0xff0000),
  'the fog distance': (s) => ((s.fog as THREE.Fog).far = 170),
  'the background': (s) => (s.background as THREE.Color).setHex(0x000000),
}

describe('the scene digest sees every change of what is drawn', () => {
  it('is the same for the same scene', () => {
    expect(digest(scene())).toEqual(digest(scene()))
  })

  for (const [what, mutate] of Object.entries(MUTATIONS)) {
    it(`changes with ${what}`, () => {
      const changed = scene()
      mutate(changed)
      expect(digest(changed)).not.toEqual(digest(scene()))
    })
  }
})
