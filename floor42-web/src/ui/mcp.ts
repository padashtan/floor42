// The MCP panel's pure parts: the form a tool's schema gives, the typed arguments it makes, the row's summary.
import { ApiError } from '../api/request'
import type { McpServerView, McpTick } from '../api/mcp'
import type { Key } from '../i18n'

export type FieldKind = 'string' | 'number' | 'boolean' | 'enum' | 'json'

export interface Field {
  name: string
  kind: FieldKind
  required: boolean
  options: string[]
}

type Schema = { type?: unknown; enum?: unknown; properties?: Record<string, Schema>; required?: unknown }

const SIMPLE: Record<string, FieldKind> = { string: 'string', number: 'number', integer: 'number', boolean: 'boolean' }

function kindOf(p: Schema): FieldKind | null {
  if (Array.isArray(p.enum) && p.enum.every((o) => typeof o === 'string')) return 'enum'
  return typeof p.type === 'string' ? (SIMPLE[p.type] ?? null) : null
}

/**
 * One field per top-level property when every one is a string, a number, a boolean or an enum; any other schema
 * (nested, array, no properties declared) gets one JSON area, so nothing is silently dropped.
 */
export function fieldsOf(schema: Record<string, unknown> | null): Field[] {
  const json: Field[] = [{ name: '', kind: 'json', required: false, options: [] }]
  const s = schema as Schema | null
  if (!s || s.type !== 'object' || !s.properties || typeof s.properties !== 'object') return json
  const required = Array.isArray(s.required) ? (s.required as string[]) : []
  const fields: Field[] = []
  for (const [name, p] of Object.entries(s.properties)) {
    const kind = p && kindOf(p)
    if (!kind) return json
    fields.push({ name, kind, required: required.includes(name), options: kind === 'enum' ? (p.enum as string[]) : [] })
  }
  return fields
}

/** Typed arguments from what was typed; an empty optional field is left out. A bad JSON area throws a SyntaxError. */
export function argumentsOf(fields: Field[], values: Record<string, string | boolean>): Record<string, unknown> {
  const json = fields.find((f) => f.kind === 'json')
  if (json) {
    const text = String(values[json.name] ?? '').trim()
    if (!text) return {}
    const parsed: unknown = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new SyntaxError('not an object')
    return parsed as Record<string, unknown>
  }
  const out: Record<string, unknown> = {}
  for (const f of fields) {
    const v = values[f.name]
    if (f.kind === 'boolean') {
      if (v === true || f.required) out[f.name] = v === true
    } else if (typeof v === 'string' && v !== '') out[f.name] = f.kind === 'number' ? Number(v) : v
  }
  return out
}

export function summaryOf(server: McpServerView): { allowed: number; total: number; changed: boolean } {
  return { allowed: server.tools.filter((x) => x.ticked).length, total: server.tools.length, changed: server.tools.some((x) => x.changed) }
}

/** The ticked set after one change, each tool with the hash shown on screen. */
export function ticksAfter(server: McpServerView, name: string, on: boolean): McpTick[] {
  return server.tools.filter((x) => (x.name === name ? on : x.ticked && !x.changed)).map((x) => ({ name: x.name, sha256: x.sha256 }))
}

/** Refusals of a tick that have a sentence of their own on screen. */
export const TICK_HINTS: Record<string, Key> = { MCP_TOOL_CHANGED: 'mcp.toolChanged', MCP_ENDPOINT_CHANGED: 'mcp.endpointChanged' }

/** A refusal as the screen shows it: the code the server sent and its sentence. */
export function refusalOf(e: unknown): { code: string; reason: string } {
  return e instanceof ApiError ? { code: e.code, reason: e.message } : { code: 'ERROR', reason: e instanceof Error ? e.message : String(e) }
}
