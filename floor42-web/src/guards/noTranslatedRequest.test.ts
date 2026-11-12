import { describe, expect, it } from 'vitest'

/**
 * A translated text never travels in a request: the UI sends a code or the user's own input, the server answers with
 * codes and the UI translates (ADR-0021). The scan reads every `api.xxx(...)` call and every `body: {...}` and refuses
 * a `t(` or `tt(` call inside its arguments. A real exception carries `// i18n-request-ok: <why>` on the same line.
 */
const OK = 'i18n-request-ok'

/** Every source of the app (tests and this folder left out), read as text. */
const SOURCES = import.meta.glob(['../**/*.ts', '../**/*.vue', '!../**/*.test.ts', '!../guards/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

/** The text between the bracket at `open` and its match; quotes and template text are skipped. */
function balanced(text: string, open: number): string {
  const closing: Record<string, string> = { '(': ')', '{': '}' }
  const stack: string[] = []
  let quote = ''
  for (let i = open; i < text.length; i++) {
    const c = text[i]
    if (quote) {
      if (c === String.fromCharCode(92)) i++
      else if (c === quote) quote = ''
      continue
    }
    if (c === "'" || c === '"' || c === '`') quote = c
    else if (c === '(' || c === '{') stack.push(closing[c])
    else if (c === ')' || c === '}') {
      stack.pop()
      if (!stack.length) return text.slice(open, i + 1)
    }
  }
  return text.slice(open)
}

export function offenders(file: string, text: string): string[] {
  const out: string[] = []
  const lines = text.split('\n')
  const starts = /\bapi\.\w+\s*\(|\bbody\s*:\s*(?=\{)|\bJSON\.stringify\s*\(/g
  for (const m of text.matchAll(starts)) {
    const open = m.index! + m[0].length - (m[0].endsWith('(') ? 1 : 0)
    const args = balanced(text, open)
    const from = text.slice(0, open).split('\n').length
    args.split('\n').forEach((line, i) => {
      if (/(^|[^\w.$])tt?\(/.test(line) && !lines[from + i - 1].includes(OK))
        out.push(`${file}:${from + i}: ${lines[from + i - 1].trim()}`)
    })
  }
  return [...new Set(out)]
}

describe('no translated text in a request', () => {
  it('catches a planted translated default in an api call and in a body', () => {
    expect(offenders('x.vue', "api.close(m, { reason: t('x.default') })")).toHaveLength(1)
    expect(offenders('x.vue', "await request('POST', u, { body: { note: tt('a') } })")).toHaveLength(1)
    expect(
      offenders('x.vue', "api.close(m, { reason: t('x') }) // i18n-request-ok: shown to the user only"),
    ).toHaveLength(0)
    expect(offenders('x.vue', "api.close(m, { reason: text.value })\nfoo(t('x'))")).toHaveLength(0)
  })

  it('finds none in the real sources', () => {
    const found = Object.entries(SOURCES).flatMap(([f, text]) => offenders(f, text))
    expect(Object.keys(SOURCES).length).toBeGreaterThan(50)
    expect(found, 'send a code or the typed input; the server never stores a translated sentence').toEqual([])
  })
})
