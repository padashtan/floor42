import { describe, expect, it } from 'vitest'
import { prettyJson } from './json'

describe('prettyJson', () => {
  it('lays out valid JSON', () => {
    expect(prettyJson('{"a":1,"b":[2]}')).toBe('{\n  "a": 1,\n  "b": [\n    2\n  ]\n}')
  })

  it('still indents JSON cut at the console limit, keeping strings whole', () => {
    expect(prettyJson('{"id":"M-7","text":"a, {b}: c","tickets":[{"id":"T-1"')).toBe(
      '{\n  "id": "M-7",\n  "text": "a, {b}: c",\n  "tickets": [\n    {\n      "id": "T-1"',
    )
  })

  it('leaves other text as it was sent', () => {
    expect(prettyJson('plain text, not JSON')).toBe('plain text, not JSON')
  })
})
