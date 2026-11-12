/**
 * Test-only: a WebGL 2 context that draws nothing and counts what is allocated on it. Enough for three.js's
 * WebGLRenderer to start, compile (every shader and program succeeds, its active uniforms are the samplers its shaders declare) and render;
 * every `createX` hands a fresh object, every `deleteX` of one counts its release.
 */

/** Live GPU objects on one context: created minus deleted, per kind. */
export interface GlCounts {
  buffers: number
  textures: number
  framebuffers: number
  renderbuffers: number
  programs: number
  shaders: number
  vertexArrays: number
}

const KINDS: Record<string, keyof GlCounts> = {
  Buffer: 'buffers',
  Texture: 'textures',
  Framebuffer: 'framebuffers',
  Renderbuffer: 'renderbuffers',
  Program: 'programs',
  Shader: 'shaders',
  VertexArray: 'vertexArrays',
}

/** A stable number per constant name, so `gl.X === gl.X` and two names differ. */
function constant(name: string): number {
  let h = 7
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 1_000_003
  return 1000 + h
}

export interface FakeGl {
  gl: WebGL2RenderingContext
  counts: GlCounts
  /** Objects created and not deleted, by kind, each with the stack that made it (`origin`): what a test can name. */
  live: Map<object, keyof GlCounts>
}

function parameter(name: string, size: { width: number; height: number }): unknown {
  if (name === 'VERSION') return 'WebGL 2.0 (fake)'
  if (name === 'SHADING_LANGUAGE_VERSION') return 'WebGL GLSL ES 3.00 (fake)'
  if (name === 'VIEWPORT' || name === 'SCISSOR_BOX') return new Int32Array([0, 0, size.width, size.height])
  if (name.startsWith('MAX_')) return 16384
  return 0
}

/** GLSL sampler types and the WebGL enums three.js's uniform setters switch on (real values, not fake constants). */
const SAMPLERS: Record<string, number> = {
  sampler2D: 0x8b5e,
  sampler3D: 0x8b5f,
  samplerCube: 0x8b60,
  sampler2DShadow: 0x8b62,
  sampler2DArray: 0x8dc1,
  sampler2DArrayShadow: 0x8dc4,
  samplerCubeShadow: 0x8dc5,
  isampler2D: 0x8dca,
  usampler2D: 0x8dd2,
}

/** One sampler a program declares: what three.js reads with getActiveUniform. */
interface ActiveSampler {
  name: string
  type: number
  size: number
}

/**
 * The samplers a program's shaders declare (`uniform sampler2D map;`), each once, arrays left out. Preprocessor
 * branches are not evaluated: a sampler declared under an `#ifdef` counts as active, so three.js binds a texture to
 * it (the material's own, or its empty default), as a driver would for every sampler the material really uses.
 */
export function declaredSamplers(sources: string[]): ActiveSampler[] {
  const found = new Map<string, ActiveSampler>()
  const declaration = /uniform\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+(\w+)\s*;/g
  for (const source of sources) {
    for (const m of source.matchAll(declaration)) {
      const type = SAMPLERS[m[1]]
      if (type !== undefined && !found.has(m[2])) found.set(m[2], { name: m[2], type, size: 1 })
    }
  }
  return [...found.values()]
}

/** Which source each shader was given and which shaders each program links: what its uniforms are read from. */
class Shaders {
  private readonly sources = new WeakMap<object, string>()
  private readonly attached = new WeakMap<object, object[]>()
  private readonly linked = new WeakMap<object, ActiveSampler[]>()

  source(shader: object, text: string): void {
    this.sources.set(shader, text)
  }

  attach(program: object, shader: object): void {
    this.attached.set(program, [...(this.attached.get(program) ?? []), shader])
  }

  link(program: object): void {
    this.linked.set(program, declaredSamplers((this.attached.get(program) ?? []).map((s) => this.sources.get(s) ?? '')))
  }

  samplers(program: object): ActiveSampler[] {
    return this.linked.get(program) ?? []
  }
}

/**
 * What the context answers when three.js asks: every compile and link succeeds; a program's active uniforms are the
 * samplers its shaders declare, so every texture a material holds is bound and uploaded; no other uniform, no attribute.
 */
function answers(names: Map<number, string>, size: { width: number; height: number }, shaders: Shaders): Record<string, unknown> {
  return {
    getContextAttributes: () => ({ alpha: true, antialias: false, depth: true, stencil: false, premultipliedAlpha: true }),
    isContextLost: () => false,
    getExtension: (name: string) => (name === 'WEBGL_lose_context' ? { loseContext: () => {}, restoreContext: () => {} } : {}),
    getSupportedExtensions: () => [],
    getParameter: (p: number) => parameter(names.get(p) ?? '', size),
    getShaderPrecisionFormat: () => ({ precision: 23, rangeMin: 127, rangeMax: 127 }),
    getShaderParameter: () => true,
    getProgramParameter: (program: object, p: number) =>
      names.get(p) === 'LINK_STATUS' ? true : names.get(p) === 'ACTIVE_UNIFORMS' ? shaders.samplers(program).length : 0,
    getActiveUniform: (program: object, i: number) => shaders.samplers(program)[i] ?? null,
    shaderSource: (shader: object, text: string) => shaders.source(shader, text),
    attachShader: (program: object, shader: object) => shaders.attach(program, shader),
    linkProgram: (program: object) => shaders.link(program),
    getShaderInfoLog: () => '',
    getProgramInfoLog: () => '',
    getShaderSource: () => '',
    getUniformLocation: (_: object, name: string) => ({ name }),
    getAttribLocation: () => -1,
    checkFramebufferStatus: () => constant('FRAMEBUFFER_COMPLETE'),
    getError: () => 0,
  }
}

export function fakeGl(size = { width: 1280, height: 720 }): FakeGl {
  const counts: GlCounts = { buffers: 0, textures: 0, framebuffers: 0, renderbuffers: 0, programs: 0, shaders: 0, vertexArrays: 0 }
  const live = new Map<object, keyof GlCounts>()
  const names = new Map<number, string>()
  const methods = answers(names, size, new Shaders())
  const gl = new Proxy({} as Record<string, unknown>, {
    get(target, key) {
      if (typeof key !== 'string') return undefined
      if (key in target) return target[key]
      if (key === 'canvas') return { width: size.width, height: size.height, addEventListener() {}, removeEventListener() {} }
      if (key === 'drawingBufferWidth') return size.width
      if (key === 'drawingBufferHeight') return size.height
      if (/^[A-Z0-9_]+$/.test(key)) {
        const n = constant(key)
        names.set(n, key)
        return n
      }
      if (key in methods) return methods[key]
      return lifecycle(key, counts, live) ?? (() => undefined)
    },
  })
  return { gl: gl as unknown as WebGL2RenderingContext, counts, live }
}

/** `createX` and `deleteX` for a kind of GL object counted: each made object keeps the stack that made it. */
function lifecycle(key: string, counts: GlCounts, live: Map<object, keyof GlCounts>): ((o: object | null) => unknown) | undefined {
  const made = /^create(\w+)$/.exec(key)?.[1]
  if (made && KINDS[made]) {
    return () => {
      const o = { kind: made, origin: new Error().stack ?? '' }
      counts[KINDS[made]]++
      live.set(o, KINDS[made])
      return o
    }
  }
  const gone = /^delete(\w+)$/.exec(key)?.[1]
  if (gone && KINDS[gone]) {
    return (o: object | null) => {
      if (!o || !live.has(o)) return
      counts[KINDS[gone]]--
      live.delete(o)
    }
  }
  return undefined
}
