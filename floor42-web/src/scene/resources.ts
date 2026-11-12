import * as THREE from 'three'

/**
 * What a scene uploads to the GPU and must give back: geometries, materials and their textures. A renderer listens to
 * each one it drew until it is disposed; a material shared between two scenes (kit.ts caches them) would otherwise keep
 * the renderer of a replaced decade, and with it the whole scene it last drew, alive.
 */

type Disposable = THREE.BufferGeometry | THREE.Material | THREE.Texture

/** The textures a material holds (map, emissiveMap...), whatever their slot. */
function texturesOf(material: THREE.Material): THREE.Texture[] {
  return Object.values(material).filter((v): v is THREE.Texture => v instanceof THREE.Texture)
}

/** Every geometry, material and texture an object and its descendants draw with, each once. */
export function resourcesOf(root: THREE.Object3D): Set<Disposable> {
  const found = new Set<Disposable>()
  root.traverse((o) => {
    const drawn = o as Partial<THREE.Mesh>
    if (drawn.geometry instanceof THREE.BufferGeometry) found.add(drawn.geometry)
    const materials = drawn.material === undefined ? [] : Array.isArray(drawn.material) ? drawn.material : [drawn.material]
    for (const m of materials) {
      found.add(m)
      for (const t of texturesOf(m)) found.add(t)
    }
  })
  return found
}

/**
 * Frees what an object and its descendants uploaded: the renderer drops its copies and its listeners. Besides
 * geometries, materials and textures: an instanced mesh's own matrices and colours, and a light's shadow map.
 */
export function disposeTree(root: THREE.Object3D): void {
  for (const r of resourcesOf(root)) r.dispose()
  root.traverse((o) => {
    if (o instanceof THREE.InstancedMesh || o instanceof THREE.Light) o.dispose()
  })
}

/**
 * Textures three.js binds to the materials of `root` by itself, outside the materials' own slots: the module-wide
 * 16x16 DFG lookup texture of every standard material is one, shared by every renderer of the page. The renderer that
 * uploads it listens for its disposal; never disposed, it keeps every replaced renderer, its WebGL context and the scene
 * it last drew alive. Read from the renderer's own uniforms of each material, so call it before the materials go.
 */
export function rendererTextures(renderer: THREE.WebGLRenderer, root: THREE.Object3D): Set<THREE.Texture> {
  const found = new Set<THREE.Texture>()
  for (const r of resourcesOf(root)) {
    if (!(r instanceof THREE.Material)) continue
    const uniforms = (renderer.properties.get(r) as { uniforms?: Record<string, { value: unknown }> }).uniforms ?? {}
    const own = new Set(texturesOf(r))
    for (const u of Object.values(uniforms)) if (u.value instanceof THREE.Texture && !own.has(u.value)) found.add(u.value)
  }
  return found
}

/** Frees a scene a renderer drew: its graph ({@link disposeTree}), then the textures three bound to it by itself. */
export function disposeDrawn(renderer: THREE.WebGLRenderer, root: THREE.Object3D): void {
  const shared = rendererTextures(renderer, root)
  disposeTree(root)
  for (const t of shared) t.dispose()
}

/**
 * Geometries and materials shared by key while a part of the scene is built, so identical ones are made once; and
 * every one of them freed together by {@link dispose}.
 */
export class ResourceCache {
  private readonly geometries = new Map<string, THREE.BufferGeometry>()
  private readonly materials = new Map<string, THREE.Material>()

  geometry<T extends THREE.BufferGeometry>(key: string, make: () => T): T {
    let g = this.geometries.get(key)
    if (!g) {
      g = make()
      this.geometries.set(key, g)
    }
    return g as T
  }

  material<T extends THREE.Material>(key: string, make: () => T): T {
    let m = this.materials.get(key)
    if (!m) {
      m = make()
      this.materials.set(key, m)
    }
    return m as T
  }

  /** A box of any size: one geometry per size. */
  box(w: number, h: number, d: number): THREE.BoxGeometry {
    return this.geometry(`box:${w}:${h}:${d}`, () => new THREE.BoxGeometry(w, h, d))
  }

  /** The plain Lambert material instanced meshes tint per instance. */
  lambert(): THREE.MeshLambertMaterial {
    return this.material('lambert', () => new THREE.MeshLambertMaterial())
  }

  /** The plain unlit material instanced meshes tint per instance. */
  basic(): THREE.MeshBasicMaterial {
    return this.material('basic', () => new THREE.MeshBasicMaterial())
  }

  dispose(): void {
    for (const g of this.geometries.values()) g.dispose()
    for (const m of this.materials.values()) {
      for (const t of texturesOf(m)) t.dispose()
      m.dispose()
    }
    this.geometries.clear()
    this.materials.clear()
  }
}
