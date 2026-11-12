import type { CDPSession, Page } from '@playwright/test'

/** The parts of a V8 heap snapshot this file reads. */
interface Snapshot {
  snapshot: { meta: { node_fields: string[]; node_types: [string[]] } }
  nodes: number[]
  strings: string[]
}

/** What one heap snapshot holds, summed up. Every node a snapshot lists is reachable: it follows a full GC. */
export interface HeapSummary {
  /** The bytes of the objects scripts made: V8's compiled code and the browser's own native objects left out. */
  scriptBytes: number
  /** The bytes of every ArrayBuffer's data (typed arrays: a geometry's vertices, a texture's pixels), outside the JS heap. */
  bufferBytes: number
  /** Canvas elements still reachable: each 2D texture of the scene draws on one. */
  canvases: number
  /** Self sizes summed by node type (object, array, code, native...). */
  byType: Map<string, number>
  /** Nodes and bytes by kind: type and constructor or native name (strings counted as one kind). */
  byKind: Map<string, { count: number; bytes: number }>
  /** Native nodes by name: one per DOM or WebGL object still reachable (WebGL2RenderingContext: one per context). */
  natives: Map<string, number>
}

/**
 * The node types of objects a script made. Left out: code (V8's compiled functions and their feedback, which go on
 * growing while the page warms up) and native nodes (the browser's own, DevTools' records of the requests and the
 * timeline's entries among them), which {@link HeapSummary.bufferBytes} and {@link HeapSummary.canvases} cover where
 * a scene's data lives: buffers' bytes and canvases.
 */
const SCRIPT_TYPES = new Set(['object', 'array', 'closure', 'string', 'concatenated string', 'sliced string', 'number', 'regexp', 'object shape', 'symbol', 'bigint'])

/** The native node holding an ArrayBuffer's bytes. */
const BUFFER_DATA = 'system / JSArrayBufferData'

/**
 * A page's heap through one DevTools session: its used size and its snapshots, each after a full garbage collection.
 * Keep it the only session that touches the heap profiler: closing another one disables the profiler. Snapshots are
 * compared by their sums, never by node id: V8 gives a new object the id of a dead one found at the same address.
 */
export class HeapProbe {
  private readonly cdp: CDPSession

  private constructor(cdp: CDPSession) {
    this.cdp = cdp
  }

  static async open(page: Page): Promise<HeapProbe> {
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('HeapProfiler.enable')
    return new HeapProbe(cdp)
  }

  /** The JS heap's used size after a full garbage collection, in MB, to a tenth (ArrayBuffers' data not included). */
  async usedMb(): Promise<number> {
    await this.cdp.send('HeapProfiler.collectGarbage')
    const { usedSize } = await this.cdp.send('Runtime.getHeapUsage')
    return Math.round((usedSize / 1048576) * 10) / 10
  }

  async snapshot(): Promise<HeapSummary> {
    await this.cdp.send('HeapProfiler.collectGarbage')
    await this.cdp.send('HeapProfiler.collectGarbage')
    const chunks: string[] = []
    const take = (e: { chunk: string }) => chunks.push(e.chunk)
    this.cdp.on('HeapProfiler.addHeapSnapshotChunk', take)
    await this.cdp.send('HeapProfiler.takeHeapSnapshot', { reportProgress: false })
    this.cdp.off('HeapProfiler.addHeapSnapshotChunk', take)
    return summarise(JSON.parse(chunks.join('')) as Snapshot)
  }

  async close(): Promise<void> {
    await this.cdp.detach()
  }
}

function summarise(heap: Snapshot): HeapSummary {
  const fields = heap.snapshot.meta.node_fields
  const width = fields.length
  const [typeAt, nameAt, sizeAt] = ['type', 'name', 'self_size'].map((f) => fields.indexOf(f))
  const types = heap.snapshot.meta.node_types[0]
  const s: HeapSummary = { scriptBytes: 0, bufferBytes: 0, canvases: 0, byType: new Map(), byKind: new Map(), natives: new Map() }
  for (let i = 0; i < heap.nodes.length; i += width) {
    const type = types[heap.nodes[i + typeAt]]
    const size = heap.nodes[i + sizeAt]
    const name = type.endsWith('string') ? '' : heap.strings[heap.nodes[i + nameAt]]
    if (SCRIPT_TYPES.has(type)) s.scriptBytes += size
    s.byType.set(type, (s.byType.get(type) ?? 0) + size)
    const kind = `${type} ${name.slice(0, 60)}`
    const k = s.byKind.get(kind) ?? { count: 0, bytes: 0 }
    k.count++
    k.bytes += size
    s.byKind.set(kind, k)
    // The wrappers of DOM and WebGL objects are native nodes; the interface's constructor and templates are not.
    if (type !== 'native') continue
    if (name === BUFFER_DATA) s.bufferBytes += size
    if (name.startsWith('<canvas')) s.canvases++
    s.natives.set(name, (s.natives.get(name) ?? 0) + 1)
  }
  return s
}

/** The kinds whose bytes changed most from `a` to `b`, one line each: bytes and count, before and after. */
export function kindsDiff(a: HeapSummary, b: HeapSummary, top = 25): string[] {
  const kinds = new Set([...a.byKind.keys(), ...b.byKind.keys()])
  const none = { count: 0, bytes: 0 }
  return [...kinds]
    .map((k) => ({ k, x: a.byKind.get(k) ?? none, y: b.byKind.get(k) ?? none }))
    .sort((p, q) => Math.abs(q.y.bytes - q.x.bytes) - Math.abs(p.y.bytes - p.x.bytes))
    .slice(0, top)
    .map(({ k, x, y }) => `${String(y.bytes - x.bytes).padStart(10)} B  ${x.count} -> ${y.count}  ${k}`)
}

/** Megabytes by node type, for a report line. */
export function typesLine(s: HeapSummary): string {
  const mb = (b: number) => (b / 1048576).toFixed(2)
  return [...s.byType].map(([t, b]) => `${t} ${mb(b)}`).join(', ') + `; buffers' data ${mb(s.bufferBytes)}; canvases ${s.canvases}`
}
