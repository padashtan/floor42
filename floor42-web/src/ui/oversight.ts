import type { OversightLine, OversightReport, OversightSection } from '../api/types'
import { type Key, tr } from '../i18n'

/**
 * The human oversight report (ADR-0075) as lines for the panel. Pure: the daemon sends codes, the page writes the
 * sentences in its own language. Every line names the journal events it rests on, numbered as the replay export does.
 */
export type Translate = (key: Key, params?: Record<string, string | number>) => string

export const SECTION_IDS = ['supervision', 'capabilities', 'actions', 'overrides', 'limits'] as const
export type OversightSectionId = (typeof SECTION_IDS)[number]

export interface Row {
  text: string
  notes: string[]
}

export interface OversightPart {
  id: OversightSectionId
  title: string
  count: number
  rows: Row[]
  capped: string | null
}

const key = <K extends string>(k: K) => k as unknown as Key

/** A code the daemon sends, in words when the catalogue knows it (a quarantine's steps and a round number stay as sent). */
const code = (c: string) => tr(`oversight.code.${c}`, c)

function row(l: OversightLine, t: Translate): Row {
  const head = t('oversight.line', { kind: t(key(`oversight.kind.${l.kind}`)), actor: l.actor })
  const scope = l.scope ? (l.scope === 'COMPANY' ? t('oversight.company') : t('oversight.scope', { scope: l.scope })) : ''
  const parts = [head, scope, l.code ? code(l.code) : '', l.basis ?? '', l.text ?? ''].filter(Boolean)
  const notes = l.events.map((e) => (e.seq == null ? `${t('oversight.company')} ${e.type}` : `#${e.seq} ${e.type}`))
  if (l.origin === 'SIMULATED') notes.push(t('oversight.origin.SIMULATED'))
  return { text: parts.join(': '), notes }
}

function part(id: OversightSectionId, s: OversightSection, t: Translate): OversightPart {
  const shown = s.items.length
  return {
    id,
    title: t(key(`oversight.section.${id}`)),
    count: s.count,
    rows: s.items.map((l) => row(l, t)),
    capped: s.count > shown ? t('oversight.capped', { shown, count: s.count }) : null,
  }
}

function limits(r: OversightReport, t: Translate): OversightPart {
  const rows: Row[] = r.masked.map((m) => ({ text: t(key(`replayExport.kind.${m.kind}`), { count: m.count }), notes: [] }))
  for (const l of r.limits) rows.push({ text: t(key(`oversight.limit.${l}`)), notes: [] })
  return { id: 'limits', title: t('oversight.section.limits'), count: rows.length, rows, capped: null }
}

/** The five sections, in order; a daemon cap shows as "200 of N". */
export function parts(r: OversightReport, t: Translate): OversightPart[] {
  return [
    part('supervision', r.supervision, t),
    part('capabilities', r.capabilities, t),
    part('actions', r.actions, t),
    part('overrides', r.overrides, t),
    limits(r, t),
  ]
}

export function headerLine(r: OversightReport, t: Translate): string {
  const m = r.mission
  return t('oversight.header', { origin: m.origin, status: m.status, events: m.events, real: m.real, simulated: m.simulated, version: r.version, at: r.generatedAt })
}

export function rowLine(r: Row): string {
  return r.notes.length ? `${r.text} (${r.notes.join('; ')})` : r.text
}
