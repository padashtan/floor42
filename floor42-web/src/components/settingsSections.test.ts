import { afterAll, describe, expect, it } from 'vitest'
import source from '../../../floor42-domain/src/main/java/dev/floor42/domain/Setting.java?raw'
import { en as mainEn } from '../i18n/en'
import { fr as mainFr } from '../i18n/fr'
import { identityEn } from '../i18n/identity.en'
import { identityFr } from '../i18n/identity.fr'
import { setLang } from '../i18n'
import {
  filterSections,
  itemId,
  normalize,
  searchText,
  SECTION_IDS,
  SECTIONS,
  SETTING_KEYS,
  type SettingsItem,
  step,
  withUnfiled,
} from './settingsSections'

const en: Record<string, string> = { ...mainEn, ...identityEn }
const fr: Record<string, string> = { ...mainFr, ...identityFr }

/**
 * The constants of a Java enum, read from its source: comments and strings dropped, then everything between the enum's
 * brace and the first `;` (or closing brace) at depth 0, split on the commas at depth 0. Whatever a constant passes to
 * its constructor (List.of, List.copyOf, a shared constant, nothing), its name is the first word; a piece that is not a
 * name fails the test instead of being skipped.
 */
function enumConstants(java: string, name: string): string[] {
  const code = java
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
  const head = new RegExp(`\\benum\\s+${name}\\b[^{]*\\{`).exec(code)
  if (!head) throw new Error(`no enum ${name}`)
  const pieces: string[] = []
  let depth = 0
  let piece = ''
  for (const ch of code.slice(head.index + head[0].length)) {
    if (depth === 0 && (ch === ';' || ch === '}')) break
    if ('({['.includes(ch)) depth++
    if (')}]'.includes(ch)) depth--
    if (depth === 0 && ch === ',') {
      pieces.push(piece)
      piece = ''
    } else piece += ch
  }
  pieces.push(piece)
  return pieces
    .map((p) => p.replace(/@\w+(\([^)]*\))?/g, '').trim())
    .filter(Boolean)
    .map((p) => {
      const m = /^([A-Za-z_]\w*)\s*(\(|\{|$)/.exec(p)
      if (!m) throw new Error(`not an enum constant: ${p.slice(0, 60)}`)
      return m[1]
    })
}

/** The settings the daemon can send. */
const SERVER_KEYS = enumConstants(source, 'Setting')

const filed = SECTIONS.flatMap((s) => s.items.filter((i) => i.kind === 'setting').map((i) => ({ key: itemId(i), in: s.id })))

describe('the settings sections', () => {
  it('read the server settings', () => {
    expect(SERVER_KEYS.length).toBeGreaterThanOrEqual(12)
    expect(SERVER_KEYS).toContain('PARALLEL_SESSIONS')
  })

  it('read any shape of enum constant, and refuse what is not one', () => {
    const java = `enum Setting {
      /** A, B; C */ A(List.of("x;y", ")")), // D, E
      B(List.copyOf(Stream.of(1, 2).toList())),
      @Deprecated C(YES_NO),
        D,
      E { String x() { return "}"; } };
      public static final int NOT_ONE = 1;
    }`
    expect(enumConstants(java, 'Setting')).toEqual(['A', 'B', 'C', 'D', 'E'])
    expect(() => enumConstants('enum Setting { A, 3B; }', 'Setting')).toThrow(/not an enum constant/)
  })

  it('know the same keys as the screen\'s type (SettingView)', () => {
    expect([...SETTING_KEYS].sort()).toEqual([...SERVER_KEYS].sort())
  })

  it('reach every server setting key in exactly one section', () => {
    const where = SERVER_KEYS.map((k) => `${k}: ${filed.filter((f) => f.key === k).map((f) => f.in).join(',') || 'none'}`)
    expect(where.filter((w) => w.includes(',') || w.endsWith('none'))).toEqual([])
  })

  it('name no setting the server does not have', () => {
    expect(filed.map((f) => f.key).filter((k) => !SERVER_KEYS.includes(k))).toEqual([])
  })

  it('hold every other line once, in the declared order', () => {
    const ids = SECTIONS.flatMap((s) => s.items.map(itemId))
    expect(new Set(ids).size).toBe(ids.length)
    expect(SECTIONS.map((s) => s.id)).toEqual([...SECTION_IDS])
  })

  it('have a name, a line and a one-line explanation in both languages', () => {
    const keys = [
      ...SECTION_IDS.flatMap((id) => [`sr.s.${id}`, `sr.d.${id}`]),
      ...SERVER_KEYS.flatMap((k) => [`settings.${k}`, `sr.line.${k}`]),
    ]
    for (const catalogue of [en, fr]) expect(keys.filter((k) => !(k in catalogue))).toEqual([])
  })

  it('file a key a newer daemon sends under Advanced', () => {
    const sections = withUnfiled(SECTIONS, [...SERVER_KEYS, 'SOMETHING_NEW'])
    expect(sections.find((s) => s.id === 'advanced')!.items.map(itemId)).toContain('SOMETHING_NEW')
    expect(sections.flatMap((s) => s.items).length).toBe(SECTIONS.flatMap((s) => s.items).length + 1)
  })
})

describe('the settings search', () => {
  afterAll(() => setLang('en'))

  it('finds a setting by an accented word of the screen\'s language', () => {
    setLang('fr')
    const found = filterSections(SECTIONS, 'réseau', (i) => searchText(i))
    expect(found.flatMap((s) => s.items.map(itemId))).toContain('SANDBOX_NETWORK')
    expect(filterSections(SECTIONS, 'RESEAU bac', (i) => searchText(i)).flatMap((s) => s.items.map(itemId))).toContain(
      'SANDBOX_NETWORK',
    )
    setLang('en')
    expect(filterSections(SECTIONS, 'tutorial', (i) => searchText(i)).map((s) => s.id)).toEqual(['general'])
  })

  it('finds a key a newer daemon sends by its name, without texts', () => {
    expect(searchText({ kind: 'setting', key: 'SOMETHING_NEW' as never }, '42')).toContain('SOMETHING_NEW')
  })

  const textOf = (i: SettingsItem) => (i.kind === 'setting' ? `${i.key} Réseau du bac à sable` : i.kind)

  it('keeps everything for an empty query', () => {
    expect(filterSections(SECTIONS, '  ', textOf)).toEqual([...SECTIONS])
  })

  it('ignores case and accents, and needs every word', () => {
    expect(normalize('  Réseau   À ')).toBe('reseau a')
    const found = filterSections(SECTIONS, 'reseau sandbox_network', textOf)
    expect(found.map((s) => s.id)).toEqual(['safety'])
    expect(found[0].items.map(itemId)).toEqual(['SANDBOX_NETWORK'])
  })

  it('drops sections without a match', () => {
    expect(filterSections(SECTIONS, 'backups', textOf).map((s) => s.id)).toEqual(['safety'])
    expect(filterSections(SECTIONS, 'nothing like this', textOf)).toEqual([])
  })

  it('moves through the sections with the arrows, wrapping around', () => {
    expect(step('general', 1)).toBe('missions')
    expect(step('general', -1)).toBe('advanced')
    expect(step('advanced', 1)).toBe('general')
  })
})
