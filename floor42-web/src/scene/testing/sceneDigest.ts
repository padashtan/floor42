import * as THREE from 'three'

/**
 * Test-only helpers: a canvas that records what is painted on it instead of painting, and a digest of a built scene
 * graph (the scene's fog and background, meshes, geometries, every parameter of every material, textures, lights and
 * their shadows, instances) that a snapshot can hold. three.js objects are plain data until a renderer uploads them.
 */

/** Every canvas made while the fake is installed, in creation order, with the calls painted on it. */
export interface FakeCanvas {
  width: number
  height: number
  log: string[]
  getContext(kind: string): unknown
}

const round = (n: number, d = 3): string => {
  const s = n.toFixed(d)
  return /^-0\.?0*$/.test(s) ? s.slice(1) : s
}

const show = (v: unknown): string => {
  if (typeof v === 'number') return round(v)
  if (typeof v === 'string' || typeof v === 'boolean' || v === undefined || v === null) return String(v)
  return '[obj]'
}

function recordingContext(canvas: FakeCanvas): unknown {
  const state: Record<PropertyKey, unknown> = {}
  const self: unknown = new Proxy(state, {
    get: (t, k) => {
      if (k in t) return t[k]
      if (k === 'canvas') return canvas
      if (k === 'measureText') return (s: string) => ({ width: s.length * 8 })
      return (...args: unknown[]) => {
        canvas.log.push(`${String(k)}(${args.map(show).join(',')})`)
        return self
      }
    },
    set: (t, k, v) => {
      canvas.log.push(`${String(k)}=${show(v)}`)
      t[k] = v
      return true
    },
  })
  return self
}

/**
 * Replaces `document.createElement`: every element is a fake canvas whose 2D context records what is painted, and whose
 * WebGL context is `gl` when one is given. Returns the list the canvases go to. Undo with vi.unstubAllGlobals.
 */
export function fakeDocument(gl?: unknown): { canvases: FakeCanvas[]; document: unknown } {
  const canvases: FakeCanvas[] = []
  const document = {
    hidden: false,
    createElement: () => {
      const c: FakeCanvas = {
        width: 300,
        height: 150,
        log: [],
        getContext: (kind: string) => (kind.startsWith('webgl') ? (gl ?? null) : context),
      }
      const context = recordingContext(c)
      canvases.push(c)
      return c
    },
  }
  return { canvases, document }
}

/** A seeded Math.random replacement, so figures, LEDs and the weather draw the same values on every run. */
export function seededRandom(seed: number): () => number {
  let s = seed
  return () => {
    s = (s * 16807) % 2147483647
    return (s - 1) / 2147483646
  }
}

/** FNV-1a over a string: short and stable, enough to tell two digests apart. */
export function hash(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}

const numbers = (a: ArrayLike<number>, limit = a.length): string => {
  const out: string[] = []
  for (let i = 0; i < Math.min(limit, a.length); i++) out.push(round(a[i]))
  return out.join(',')
}

function textureLine(t: THREE.Texture | null | undefined): string {
  if (!t) return '-'
  const img = t.image as Partial<FakeCanvas> | undefined
  const painted = img?.log ? hash(img.log.join(';')) : 'none'
  return `${t.type}:${img?.width}x${img?.height}:${painted}:r${round(t.repeat.x)},${round(t.repeat.y)}:w${t.wrapS},${t.wrapT}:f${t.magFilter},${t.minFilter}:${t.colorSpace}`
}

/** Keys of an object that say nothing of what is drawn. */
const IGNORED = new Set(['uuid', 'id', 'name', 'version', 'userData', '_listeners', 'isMaterial', 'type', 'image', 'source', 'onBeforeCompile', 'onBuild', 'onBeforeRender'])

/** One value of a parameter: numbers rounded, colours in hex, textures described, vectors by component. */
function value(v: unknown): string | null {
  if (v === null || v === undefined) return String(v)
  if (typeof v === 'number') return round(v)
  if (typeof v === 'string' || typeof v === 'boolean') return String(v)
  if (v instanceof THREE.Color) return v.getHexString()
  if (v instanceof THREE.Texture) return `{${textureLine(v)}}`
  if (v instanceof THREE.Vector2 || v instanceof THREE.Vector3 || v instanceof THREE.Vector4 || v instanceof THREE.Euler) {
    return v.toArray().map((n) => (typeof n === 'number' ? round(n) : String(n))).join(',')
  }
  if (Array.isArray(v)) return `[${v.map(value).join(',')}]`
  if (typeof v === 'object' && 'value' in v) return value((v as { value: unknown }).value)
  if (typeof v === 'object') return `{${parameters(v)}}`
  return null
}

/** Every own parameter of an object (a material, a shader's uniforms), sorted, as key=value. */
function parameters(o: object): string {
  return Object.keys(o)
    .filter((k) => !IGNORED.has(k))
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .map((k) => {
      const v = value((o as Record<string, unknown>)[k])
      return v === null ? '' : `${k.replace(/^_/, '')}=${v}`
    })
    .filter((x) => x !== '')
    .join(' ')
}

/** Everything a material holds that can change what is drawn: colours, roughness, emissive, every texture slot... */
export function materialLine(m: THREE.Material): string {
  return `${m.type} ${parameters(m)}`
}

/** A light: its colour and strength, its reach and cone, its shadow, and where it aims. */
function lightLine(l: THREE.Light): string {
  const r = l as THREE.Light & Partial<THREE.SpotLight> & Partial<THREE.HemisphereLight> & { shadow?: THREE.LightShadow }
  const parts = [`light ${l.color.getHexString()} i${round(l.intensity)}`]
  if (r.groundColor) parts.push(`ground ${r.groundColor.getHexString()}`)
  if (r.distance !== undefined) parts.push(`d${round(r.distance)}`)
  if (r.decay !== undefined) parts.push(`decay${round(r.decay)}`)
  if (r.angle !== undefined) parts.push(`angle${round(r.angle)} penumbra${round(r.penumbra ?? 0)}`)
  if (r.shadow) {
    const s = r.shadow
    parts.push(`shadow bias${round(s.bias, 5)} nbias${round(s.normalBias, 5)} radius${round(s.radius)} map${s.mapSize.x}x${s.mapSize.y}`)
    const c = s.camera as unknown as Partial<THREE.OrthographicCamera>
    if (c.left !== undefined) parts.push(`cam${round(c.left)},${round(c.right!)},${round(c.top!)},${round(c.bottom!)}`)
  }
  if (r.target) parts.push(`target ${value(r.target.position)}`)
  return parts.join(' ')
}

/** What a scene shows besides its objects: its background and its fog. */
export function sceneLine(scene: THREE.Scene): string {
  const fog = scene.fog as THREE.Fog | null
  const background = scene.background instanceof THREE.Color ? scene.background.getHexString() : String(scene.background)
  return `scene background ${background} fog ${fog ? `${fog.color.getHexString()} ${round(fog.near)}-${round(fog.far)}` : 'none'}`
}

export function geometryLine(g: THREE.BufferGeometry): string {
  const pos = g.getAttribute('position')
  const attrs = Object.keys(g.attributes).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join('+')
  const index = g.index ? g.index.count : 0
  const all = Object.keys(g.attributes)
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .map((k) => numbers(g.getAttribute(k).array as ArrayLike<number>))
    .join('|')
  return `${g.type} v${pos?.count ?? 0} i${index} ${attrs} ${hash(all + (g.index ? numbers(g.index.array) : ''))}`
}

function instancesLine(m: THREE.InstancedMesh): string {
  const matrices = numbers(m.instanceMatrix.array, m.count * 16)
  const colors = m.instanceColor ? numbers(m.instanceColor.array, m.count * 3) : ''
  return `x${m.count}/${m.instanceMatrix.count} ${hash(matrices + '|' + colors)}`
}

/** One line per object of the graph, depth-first, with its local transform and what it draws. */
export function describe(root: THREE.Object3D): string[] {
  const lines: string[] = []
  const walk = (o: THREE.Object3D, depth: number) => {
    const p = o.position
    const q = o.quaternion
    const s = o.scale
    const where = `p${round(p.x)},${round(p.y)},${round(p.z)} q${round(q.x)},${round(q.y)},${round(q.z)},${round(q.w)} s${round(s.x)},${round(s.y)},${round(s.z)}`
    const flags = `${o.visible ? '' : 'hidden '}${o.renderOrder ? `ro${o.renderOrder} ` : ''}${o.castShadow ? 'cs ' : ''}${o.receiveShadow ? 'rs ' : ''}`
    let what = ''
    if (o instanceof THREE.Mesh || o instanceof THREE.Points || o instanceof THREE.Line || o instanceof THREE.Sprite) {
      const mats = Array.isArray(o.material) ? o.material : [o.material]
      what = ` [${geometryLine(o.geometry)}] {${mats.map(materialLine).join(' / ')}}`
      if (o instanceof THREE.InstancedMesh) what += ` ${instancesLine(o)}`
    } else if (o instanceof THREE.Light) {
      what = ` ${lightLine(o)}`
    }
    lines.push(`${'  '.repeat(depth)}${o.type}${o.name ? ` "${o.name}"` : ''} ${flags}${where}${what}`)
    o.children.forEach((c) => walk(c, depth + 1))
  }
  walk(root, 0)
  return lines
}

/**
 * The digest a snapshot holds: one line per direct child of `root` (its subtree's size and hash), so a change points
 * at the part of the scene it touched, and a total.
 */
export function digest(root: THREE.Object3D): string[] {
  const out: string[] = root instanceof THREE.Scene ? [sceneLine(root)] : []
  let total = 0
  root.children.forEach((child, i) => {
    const lines = describe(child)
    total += lines.length
    let meshes = 0
    let instances = 0
    child.traverse((o) => {
      if (o instanceof THREE.Mesh || o instanceof THREE.Points || o instanceof THREE.Sprite) meshes++
      if (o instanceof THREE.InstancedMesh) instances += o.count
    })
    out.push(`${i} ${lines[0].trim().split(' ')[0]} objects=${lines.length} meshes=${meshes} instances=${instances} ${hash(lines.join('\n'))}`)
  })
  out.push(`total objects=${total}`)
  return out
}
