// contract.ts compares the field NAMES of the hand-written views with openapi.json; the JSON import is widened by
// TypeScript (every `type` is a string[]), so the field TYPES cannot be compared in the type system. This test
// compares them: the kind (string, number, boolean, array, object) of each field of every checked view.
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import spec from './openapi.json'

type Kind = 'string' | 'number' | 'boolean' | 'array' | 'object' | 'any'
type Schema = {
  type?: string | string[]
  enum?: unknown[]
  $ref?: string
  anyOf?: Schema[]
  oneOf?: Schema[]
  allOf?: Schema[]
  items?: Schema
  properties?: Record<string, Schema>
}

// No Node types in the app's tsconfig: paths come from the module URL, files through the compiler's own system.
const here = decodeURIComponent(new URL('.', import.meta.url).pathname).replace(/^\/(?=[A-Za-z]:)/, '').replace(/\/$/, '')
const resolve = (...parts: string[]) => ts.sys.resolvePath(parts.join('/'))
const readFileSync = (path: string, _encoding: string) => ts.sys.readFile(path)!
const schemas = (spec as unknown as { components: { schemas: Record<string, Schema> } }).components.schemas

function schemaKind(s: Schema): Kind {
  if (s.$ref) return schemaKind(schemas[s.$ref.split('/').pop()!] ?? {})
  const alternatives = [...(s.anyOf ?? []), ...(s.oneOf ?? []), ...(s.allOf ?? [])].filter((x) => x.type !== 'null')
  if (alternatives.length === 1) return schemaKind(alternatives[0])
  if (alternatives.length > 1) return 'any'
  if (s.enum) return 'string'
  const types = (Array.isArray(s.type) ? s.type : s.type ? [s.type] : []).filter((t) => t !== 'null')
  if (types.length !== 1) return 'any'
  switch (types[0]) {
    case 'string':
      return 'string'
    case 'integer':
    case 'number':
      return 'number'
    case 'boolean':
      return 'boolean'
    case 'array':
      return 'array'
    default:
      return 'object'
  }
}

function tsKind(checker: ts.TypeChecker, type: ts.Type): Kind {
  const parts = (type.isUnion() ? type.types : [type]).filter(
    (t) => !(t.flags & (ts.TypeFlags.Null | ts.TypeFlags.Undefined | ts.TypeFlags.Void)),
  )
  const kinds = new Set<Kind>(
    parts.map((t) => {
      if (t.flags & ts.TypeFlags.StringLike) return 'string'
      if (t.flags & ts.TypeFlags.NumberLike) return 'number'
      if (t.flags & ts.TypeFlags.BooleanLike) return 'boolean'
      if (checker.isArrayType(t) || checker.isTupleType(t)) return 'array'
      if (t.flags & (ts.TypeFlags.Any | ts.TypeFlags.Unknown)) return 'any'
      return 'object'
    }),
  )
  return kinds.size === 1 ? [...kinds][0] : 'any'
}

/** The `UI.Name` / `'SchemaName'` pairs contract.ts checks, with the module each alias imports. */
function checkedPairs(source: string): { module: string; name: string; schema: string }[] {
  const modules = new Map<string, string>()
  for (const m of source.matchAll(/import type \* as (\w+) from '\.\/(\w+(?:\.\w+)*)'/g)) modules.set(m[1], m[2])
  return [...source.matchAll(/Mismatch<(\w+)\.(\w+), '(\w+)'>/g)].map((m) => ({
    module: modules.get(m[1])!,
    name: m[2],
    schema: m[3],
  }))
}

/** Field kinds that disagree between the views written by hand and the server's schemas. */
export function typeMismatches(overrides: Record<string, string> = {}): string[] {
  const contract = resolve(here, 'contract.ts')
  const source = overrides['contract.ts'] ?? readFileSync(contract, 'utf8')
  const config = ts.readConfigFile(resolve(here, '../../tsconfig.app.json'), ts.sys.readFile)
  const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, resolve(here, '../..'))
  const host = ts.createCompilerHost(parsed.options)
  const read = host.readFile.bind(host)
  host.readFile = (f) => overrides[f.replaceAll(String.fromCharCode(92), '/').split('/').pop()!] ?? read(f)
  const program = ts.createProgram([contract], { ...parsed.options, noEmit: true }, host)
  const checker = program.getTypeChecker()
  const out: string[] = []
  for (const p of checkedPairs(source)) {
    const file = program.getSourceFile(resolve(here, p.module + '.ts'))!
    const moduleSymbol = checker.getSymbolAtLocation(file)!
    const symbol = checker.getExportsOfModule(moduleSymbol).find((s) => s.name === p.name)
    if (!symbol) continue
    const declared = checker.getDeclaredTypeOfSymbol(symbol)
    const props = schemas[p.schema]?.properties ?? {}
    for (const prop of checker.getPropertiesOfType(declared)) {
      const schema = props[prop.name]
      if (!schema) continue
      const ui = tsKind(checker, checker.getTypeOfSymbol(prop))
      const server = schemaKind(schema)
      if (ui !== 'any' && server !== 'any' && ui !== server)
        out.push(`${p.name}.${prop.name}: the UI says ${ui}, the server's ${p.schema} says ${server}`)
    }
  }
  return out
}

describe('contract field types', () => {
  it('every view field has the kind of the server schema field', () => {
    expect(typeMismatches()).toEqual([])
  })

  it('a field whose type changed is reported', () => {
    const types = readFileSync(resolve(here, 'types.tickets.ts'), 'utf8')
    const edited = types.replace(/(interface TicketView \{[^}]*?\btoRecheck\??: )boolean/, '$1string')
    expect(edited).not.toBe(types)
    expect(typeMismatches({ 'types.tickets.ts': edited })).toContain(
      "TicketView.toRecheck: the UI says string, the server's TicketView says boolean",
    )
  })
})
