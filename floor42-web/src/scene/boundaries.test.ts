import { describe, expect, it } from 'vitest'

/**
 * The scene must not depend on Vue: it is framework-free (OfficeScene.ts), the HUD tells it what to draw and it reports
 * what the director points at. So nothing under src/scene imports Vue, Pinia, or anything of the UI layers (ui,
 * components, state, i18n); the UI maps the scene's events to its actions.
 */
const FORBIDDEN = /^(vue|pinia|vue-router|@vue\/.*)$|^(\.\.\/)+(ui|components|state|i18n|App)(\/|$|\.vue)/

/** Every source of src/scene, as text (Vite reads them: no Node types needed). */
const sources = import.meta.glob<string>('./**/*.{ts,vue}', { query: '?raw', import: 'default', eager: true })

const specifiers = (text: string): string[] => [...text.matchAll(/(?:from|import)\s*\(?\s*['"]([^'"]+)['"]/g)].map((m) => m[1])

describe('the scene layer', () => {
  it('imports nothing from Vue or from the UI layers', () => {
    const files = Object.entries(sources)
    expect(files.length, 'the scan finds the scene sources').toBeGreaterThan(30)
    const offenders = files.flatMap(([f, text]) => specifiers(text).filter((s) => FORBIDDEN.test(s)).map((s) => `${f} imports ${s}`))
    expect(offenders).toEqual([])
  })

  it('the rule catches what it is meant to', () => {
    for (const s of ['vue', '@vue/reactivity', 'pinia', '../ui/actions', '../../components/catalog', '../state/office', '../i18n'])
      expect(FORBIDDEN.test(s), s).toBe(true)
    for (const s of ['three', './kit', '../api/types', 'node:fs']) expect(FORBIDDEN.test(s), s).toBe(false)
  })
})
