import { describe, expect, it } from 'vitest'
import type { McpServerView } from '../api/mcp'
import { ApiError } from '../api/request'
import { argumentsOf, fieldsOf, refusalOf, summaryOf, ticksAfter } from './mcp'

const flat = {
  type: 'object',
  required: ['query'],
  properties: {
    query: { type: 'string' },
    limit: { type: 'integer' },
    exact: { type: 'boolean' },
    sort: { type: 'string', enum: ['asc', 'desc'] },
  },
}

describe('the form a tool schema gives', () => {
  it('a string, a number, a boolean and an enum become fields', () => {
    expect(fieldsOf(flat).map((f) => [f.name, f.kind, f.required])).toEqual([
      ['query', 'string', true],
      ['limit', 'number', false],
      ['exact', 'boolean', false],
      ['sort', 'enum', false],
    ])
    expect(fieldsOf(flat)[3].options).toEqual(['asc', 'desc'])
  })

  it('a nested schema falls back to one JSON area', () => {
    const nested = { type: 'object', properties: { a: { type: 'string' }, b: { type: 'object', properties: {} } } }
    for (const schema of [nested, null, { type: 'array' }]) {
      expect(fieldsOf(schema as Record<string, unknown> | null).map((f) => f.kind)).toEqual(['json'])
    }
  })

  it('values become typed arguments', () => {
    const fields = fieldsOf(flat)
    expect(argumentsOf(fields, { query: 'x', limit: '3', exact: true, sort: '' })).toEqual({ query: 'x', limit: 3, exact: true })
    expect(argumentsOf(fields, { query: 'x' })).toEqual({ query: 'x' })
  })

  it('a JSON area is parsed, and a bad one is refused', () => {
    const json = fieldsOf(null)
    expect(argumentsOf(json, { '': '{"a":[1]}' })).toEqual({ a: [1] })
    expect(argumentsOf(json, {})).toEqual({})
    expect(() => argumentsOf(json, { '': '{nope' })).toThrow(SyntaxError)
    expect(() => argumentsOf(json, { '': '[1]' })).toThrow(SyntaxError)
  })
})

describe('the server row', () => {
  const tool = (name: string, ticked: boolean, changed = false) => ({ name, description: null, inputSchema: null, sha256: name, ticked, changed })
  const server = (tools: McpServerView['tools']): McpServerView => ({ resource: 'docs', path: '/mcp', baseUrl: 'https://d.example', audience: 'd', pinnedAt: null, tools })

  it('the summary counts allowed and changed tools', () => {
    const s = server([tool('a', true), tool('b', true), tool('c', false), tool('d', false, true), tool('e', false)])
    expect(summaryOf(s)).toEqual({ allowed: 2, total: 5, changed: true })
    expect(summaryOf(server([tool('a', true)])).changed).toBe(false)
  })

  it('a tick lists every ticked tool with the hash on screen, the changed ones left out', () => {
    const s = server([tool('a', true), tool('b', false), tool('c', true, true)])
    expect(ticksAfter(s, 'b', true)).toEqual([{ name: 'a', sha256: 'a' }, { name: 'b', sha256: 'b' }])
    expect(ticksAfter(s, 'a', false)).toEqual([])
  })

  it('a refusal keeps its code and its sentence', () => {
    expect(refusalOf(new ApiError(409, 'HOST_NOT_ALLOWED', 'the host is not allowed'))).toEqual({ code: 'HOST_NOT_ALLOWED', reason: 'the host is not allowed' })
    expect(refusalOf(new Error('boom')).reason).toBe('boom')
  })
})
