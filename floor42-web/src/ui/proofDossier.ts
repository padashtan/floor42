import type { DossierDelivery, DossierIdentity, DossierRef, ProofDossier } from '../api/types'
import type { Key } from '../i18n'

/**
 * The proof dossier (ADR-0071) as lines for the panel and as a Markdown file. Pure: the page writes the Markdown from
 * the masked JSON, in its own language; the server renders no label. Every line names its source and, when it comes
 * from the journal, the numbers of the events it rests on (the replay export numbers them the same way).
 */
export type Translate = (key: Key, params?: Record<string, string | number>) => string

export const SECTION_IDS = ['identities', 'refusals', 'approvals', 'costs', 'checks', 'deliverables', 'diff', 'masked'] as const
export type DossierSectionId = (typeof SECTION_IDS)[number]

export interface Row {
  text: string
  /** The source words, then the basis word of a figure, then the event numbers (`#12 ActionDenied`). */
  notes: string[]
}

export interface DossierPart {
  id: DossierSectionId
  title: string
  /** The full count; `rows` holds at most the 200 the server sent. */
  count: number
  rows: Row[]
  capped: string | null
}

/**
 * Text written by an agent or a command, made inert in Markdown: no link, image, table cell, HTML or code span can
 * be made of it, and a bare address does not become a link.
 */
export function inert(text: string): string {
  return text
    .replace(/\s+/g, ' ')
    // Entities, not backslashes, for HTML: a renderer that allows raw HTML must not see a tag at all.
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/[\\`*_[\]()|!#~]/g, (c) => `\\${c}`)
    .replace(/:\/\//g, ':\\/\\/')
}

const key = <K extends string>(k: K) => k as unknown as Key

function sourceWords(ref: DossierRef | null | undefined, d: ProofDossier, t: Translate): string {
  const source = ref?.source ?? 'DERIVED'
  return t(key(`dossier.source.${source}`), { at: d.gitReadAt ?? d.generatedAt })
}

function notes(ref: DossierRef | null | undefined, d: ProofDossier, t: Translate, extra: string[] = []): string[] {
  const out = [sourceWords(ref, d, t), ...extra]
  if (ref?.basis) out.push(t(key(`dossier.basis.${ref.basis}`)))
  if (ref && ref.source === 'JOURNAL') out.push(...ref.events.map((e) => `#${e.seq} ${e.type}`))
  return out
}

const usd = (n: number) => n.toFixed(4).replace(/\.?0+$/, '') || '0'
const dash = (s: string | null | undefined) => s ?? ''

/** The token's claims as the journal recorded them (ADR-0074); one line for a session that ran before. */
function claims(i: DossierIdentity, d: ProofDossier, t: Translate): Row[] {
  if (!i.subject) return [{ text: t('dossier.claim.older'), notes: [sourceWords(null, d, t)] }]
  const recorded = notes(i.ref, d, t, [t('dossier.claim.recorded')])
  const claim = (label: Key, value: string | null | undefined): Row => ({ text: `${t(label)}: ${dash(value)}`, notes: recorded })
  return [
    claim('dossier.claim.sub', i.subject),
    claim('dossier.claim.azp', i.authorizedParty),
    claim('dossier.claim.act', i.actor),
    claim('dossier.claim.scopes', i.scopes?.join(' ')),
  ]
}

function identities(d: ProofDossier, t: Translate): Row[] {
  if (!d.iam) return [{ text: t('dossier.iamOff'), notes: [sourceWords(null, d, t)] }]
  const rows: Row[] = d.identities.items.flatMap((i) => [
    {
      text: t('dossier.identity', { agent: i.agent, client: i.client, jti: i.jti, issuer: i.issuer, expires: i.expiresAt }),
      notes: notes(i.ref, d, t, [`${sourceWords(null, d, t)}: ${i.derived.join(', ')}`]),
    },
    ...claims(i, d, t),
  ])
  for (const b of d.brokerCalls.items) {
    rows.push({
      text: t('dossier.broker', { agent: b.agent, resource: b.resource, method: b.method, path: b.path, status: b.status, audience: b.audience }),
      notes: notes(b.ref, d, t),
    })
  }
  const g = d.delegation
  if (g) {
    const text = t('dossier.delegation', { resources: g.resources.join(', '), granted: dash(g.grantedAt), expires: dash(g.expiresAt) })
    rows.push({ text: g.revoked ? `${text} ${t('dossier.delegationRevoked')}` : text, notes: notes(g.ref, d, t) })
  }
  return rows
}

function refusals(d: ProofDossier, t: Translate): Row[] {
  return d.refusals.items.map((r) => ({
    text: t('dossier.refusal', { agent: r.agent, tool: r.tool, reason: r.reason, detail: dash(r.detail) }).trim(),
    notes: notes(r.ref, d, t),
  }))
}

function approvals(d: ProofDossier, t: Translate): Row[] {
  const rows: Row[] = d.approvals.items.map((a) => ({
    text: `${t(key(`dossier.kind.${a.kind}`))} (${a.actor})${a.text ? `: ${a.text}` : ''}`,
    notes: notes(a.ref, d, t),
  }))
  for (const c of d.authorized) {
    rows.push({ text: t('dossier.authorized', { count: c.count, basis: c.basis }), notes: notes(null, d, t) })
  }
  return rows
}

function costs(d: ProofDossier, t: Translate): Row[] {
  const c = d.costs
  const rows: Row[] = []
  if (c.budgetUsd != null) rows.push({ text: t('dossier.cost.budget', { usd: usd(c.budgetUsd) }), notes: notes(c.budgetRef, d, t) })
  for (const s of c.sessions) {
    const text =
      s.usd == null
        ? `${s.agent ?? ''}, ${s.session}: ${t('dossier.cost.none')}`
        : t('dossier.cost.session', { agent: s.agent ?? '', session: s.session, usd: usd(s.usd) })
    rows.push({ text, notes: notes(s.ref, d, t) })
  }
  if (c.total) {
    // The sum is Floor42's, read from the journal's figures: derived, never recorded.
    rows.push({ text: t('dossier.cost.total', { usd: usd(c.total.usd) }), notes: [sourceWords(null, d, t), t(key(`dossier.basis.${c.total.basis}`))] })
  }
  if (c.bound) {
    rows.push({
      text: t('dossier.cost.bound', { sessions: c.bound.sessions, usd: usd(c.bound.usd) }),
      notes: [sourceWords(null, d, t), t(key(`dossier.basis.${c.bound.basis}`))],
    })
  }
  return rows
}

function checks(d: ProofDossier, t: Translate): Row[] {
  return d.checks.items.map((c) => ({
    text: c.timedOut
      ? t('dossier.check.timedOut', { command: c.command, ms: c.durationMs, trigger: dash(c.trigger) })
      : t('dossier.check', { command: c.command, code: c.exitCode ?? '', ms: c.durationMs, trigger: dash(c.trigger) }),
    notes: notes(c.ref, d, t),
  }))
}

/** Git's word on a commit's signature, as a phrase; null where the dossier holds none (ADR-0077). */
function signature(s: DossierDelivery['signature'], t: Translate): string | null {
  if (!s) return null
  const retired = s.status === 'VERIFIED' && s.retired
  return t(key(`dossier.signature.${s.status}${retired ? '.retired' : ''}`), { agent: s.agent ?? '' })
}

function deliverables(d: ProofDossier, t: Translate): Row[] {
  return d.deliverables.items.map((x) => {
    const parts = [
      x.text,
      x.commit ? `commit ${x.commit}` : null,
      x.files != null ? `${x.files} file(s)` : null,
      signature(x.signature, t),
    ].filter(Boolean)
    return { text: `${t(key(`dossier.kind.${x.kind}`))}${parts.length ? `: ${parts.join(', ')}` : ''}`, notes: notes(x.ref, d, t) }
  })
}

function diff(d: ProofDossier, t: Translate): Row[] {
  return d.diff.items.map((x) => ({
    text: x.readable
      ? t('dossier.diff.branch', { agent: x.agent, branch: dash(x.branch), commits: x.commits, uncommitted: x.uncommitted })
      : t('dossier.diff.unreadable', { agent: x.agent }),
    notes: notes(x.ref, d, t),
  }))
}

function masked(d: ProofDossier, t: Translate): Row[] {
  const rows: Row[] = d.masked.map((m) => ({ text: t(key(`replayExport.kind.${m.kind}`), { count: m.count }), notes: notes(null, d, t) }))
  for (const l of d.limits) rows.push({ text: t(key(`dossier.limit.${l}`)), notes: notes(null, d, t) })
  return rows
}

const BUILD: Record<DossierSectionId, (d: ProofDossier, t: Translate) => Row[]> = {
  identities,
  refusals,
  approvals,
  costs,
  checks,
  deliverables,
  diff,
  masked,
}

/** The sections the server caps at 200 lines, whose full count is not the rows shown. */
const CAPPED: Partial<Record<DossierSectionId, (d: ProofDossier) => { count: number; items: unknown[] }>> = {
  identities: (d) => d.identities,
  refusals: (d) => d.refusals,
  approvals: (d) => d.approvals,
  checks: (d) => d.checks,
  deliverables: (d) => d.deliverables,
  diff: (d) => d.diff,
}

/** The eight sections, in order. A server cap shows as "200 of N". */
export function parts(d: ProofDossier, t: Translate): DossierPart[] {
  return SECTION_IDS.map((id) => {
    const rows = BUILD[id](d, t)
    const section = CAPPED[id]?.(d)
    const count = id === 'identities' && !d.iam ? 1 : (section?.count ?? rows.length)
    const shown = section?.items.length ?? count
    return {
      id,
      title: t(key(`dossier.section.${id}`)),
      count,
      rows,
      capped: count > shown ? t('dossier.capped', { shown, count }) : null,
    }
  })
}

/** The header sentence under the title. */
export function headerLine(d: ProofDossier, t: Translate): string {
  return t('dossier.header', {
    origin: d.mission.origin,
    status: d.mission.status,
    events: d.mission.events,
    real: d.mission.real,
    simulated: d.mission.simulated,
    version: d.version,
    at: d.generatedAt,
  })
}

export function rowLine(r: Row): string {
  return `${r.text} (${r.notes.join('; ')})`
}

/** The Markdown file: the title, the statement, the header, then each section; agent text is made inert. */
export function toMarkdown(d: ProofDossier, t: Translate): string {
  const out = [`# ${inert(t('dossier.title', { mission: d.mission.id }))}`, '', t('dossier.statement'), '', inert(headerLine(d, t)), '']
  for (const p of parts(d, t)) {
    out.push(`## ${p.title}`, '')
    if (p.rows.length === 0) out.push(t('dossier.none'))
    for (const r of p.rows) out.push(`- ${inert(r.text)} (${r.notes.join('; ')})`)
    if (p.capped) out.push('', p.capped)
    out.push('')
  }
  return out.join('\n')
}
