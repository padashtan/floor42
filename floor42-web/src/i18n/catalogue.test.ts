import { beforeAll, describe, expect, it } from 'vitest'
import { en as mainEn } from './en'
import codes from './event-codes.json'
import { eventText, TAILS } from './events'
import { fr as mainFr } from './fr'
import { unsafeEn } from './unsafe.en'
import { unsafeFr } from './unsafe.fr'
import { setLang } from './index'

/**
 * Every code the server can emit, with the values it always sends and one sample with the English line the server
 * writes for it (a replay file). Written by the server's EventCatalogueContractTest: refresh it with
 * `./mvnw verify -Dfloor42.openapi.write=true`.
 */
interface Emitted {
  params: string[]
  sample: Record<string, string>
  line: string
}
const EMITTED = codes as Record<string, Emitted>
// The merged catalogues (en.ts and fr.ts are at their ceilings: per-area modules hold the newer lines).
const en: Record<string, string> = { ...mainEn, ...unsafeEn }
const fr: Record<string, string> = { ...mainFr, ...unsafeFr }
const CATALOGUES: Record<'en' | 'fr', Record<string, string>> = { en, fr }

function placeholders(text: string): string[] {
  return [...new Set([...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]))].sort()
}

/** The keys a code's line is made of: its own, then the tails its type may carry. */
function keysOf(code: string): string[] {
  const tails = (TAILS[code.split('.')[0]] ?? []).map(([key]) => key as string)
  return [`ev.${code}`, ...tails]
}

describe('the event catalogues', () => {
  beforeAll(() => setLang('en'))

  it('know every code the server can emit, in English and in French', () => {
    const missing = Object.keys(EMITTED).flatMap((code) =>
      (['en', 'fr'] as const).flatMap((lang) =>
        keysOf(code)
          .filter((key) => !(key in CATALOGUES[lang]))
          .map((key) => `${lang}: ${key}`),
      ),
    )
    expect(missing).toEqual([])
  })

  it('use only placeholders the server fills, the same in both languages', () => {
    const wrong = Object.entries(EMITTED).flatMap(([code, emitted]) =>
      keysOf(code).flatMap((key) => {
        const used = { en: placeholders(en[key] ?? ''), fr: placeholders(fr[key] ?? '') }
        const unfilled = [...used.en, ...used.fr].filter((name) => !emitted.params.includes(name))
        const problems = unfilled.map((name) => `${key}: {${name}} is not among the values of ${code}`)
        if (used.en.join() !== used.fr.join()) problems.push(`${key}: en uses {${used.en}}, fr uses {${used.fr}}`)
        return problems
      }),
    )
    expect([...new Set(wrong)]).toEqual([])
  })

  it('have no line the server never emits', () => {
    const emitted = new Set(Object.keys(EMITTED).map((code) => `ev.${code}`))
    const dead = Object.keys(en).filter((key) => key.startsWith('ev.') && !key.startsWith('ev.more.') && !emitted.has(key))
    expect(dead).toEqual([])
  })

  it('write in English the very line the server writes for a replay file', () => {
    const different = Object.entries(EMITTED)
      .filter(([code, emitted]) => eventText(code, emitted.sample) !== emitted.line)
      .map(([code, emitted]) => `${code}: screen "${eventText(code, emitted.sample)}", server "${emitted.line}"`)
    expect(different).toEqual([])
  })
})
