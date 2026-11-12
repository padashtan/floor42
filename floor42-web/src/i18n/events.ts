import { en } from './en'
import { identityEn } from './identity.en'
import { unsafeEn } from './unsafe.en'
import { t, tr, type Key } from './index'

/** Event fields holding a fixed server word, shown with the screen's word for it. */
const WORDS: Record<string, string> = {
  verdict: 'verdict',
  declared: 'verdict',
  criticality: 'criticality',
  workRole: 'role',
  audience: 'role',
}

/**
 * Optional tails some lines carry, each shown only when its field says so. The server writes the same tails for a
 * replay file (EnglishLines.java).
 */
export const TAILS: Record<string, [Key, (p: Record<string, string>) => boolean][]> = {
  CommitteeContributed: [
    ['ev.more.proposal', (p) => !!p.proposal],
    ['ev.more.objection', (p) => !!p.criticalObjection],
  ],
  CommitteeArbitrated: [
    ['ev.more.despite', (p) => p.overCriticalObjection === 'true'],
    ['ev.more.implement', (p) => p.implement === 'true'],
  ],
  BranchesMerged: [['ev.more.detail', (p) => !!p.detail?.trim()]],
}

/**
 * The subject or context Floor42 wrote for a question, in the screen's language: from the question's phrase when the
 * catalogue has that part, else as recorded (an agent's own words, or a question older than phrases).
 */
export function questionText(
  code: string | null | undefined,
  params: Record<string, string> | null | undefined,
  part: 'subject' | 'context',
  recorded: string,
): string {
  const key = `q.${code}.${part}`
  return code && key in en ? t(key as Key, params ?? {}) : recorded
}

/**
 * The context of a question in the screen's language. The journal keeps the English sentence Floor42 wrote (an
 * approval's scope, "Your answer applies to ..."); the server reads it back as a code and values (contextCode), and the
 * screen writes it from them. Any other context goes as `questionText` shows it.
 */
export function questionContext(q: {
  context: string | null
  contextCode: string | null
  contextParams: Record<string, string> | null
  phraseCode: string | null
  phraseParams: Record<string, string> | null
}): string {
  const key = `q.ctx.${q.contextCode}`
  if (q.contextCode && key in en) return t(key as Key, q.contextParams ?? {})
  return questionText(q.phraseCode, q.phraseParams, 'context', q.context ?? '')
}

/**
 * Why Floor42 allowed or denied a call, in the screen's language: from the code and values the server read in the
 * recorded sentence (DecisionBasis.java), else as recorded (a guard's finding, a tool's own error). The decision, a
 * category at the head of the target and a mission status are the screen's words for them.
 */
export function basisText(code: string | null | undefined, params: Record<string, string> | null | undefined, recorded: string): string {
  return codedText('basis', code, params, recorded)
}

/** A reason or a description Floor42 wrote itself (RecordedReason.java), in the screen's language, else as recorded. */
export function saidText(code: string | null | undefined, params: Record<string, string> | null | undefined, recorded: string): string {
  return codedText('said', code, params, recorded)
}

/**
 * The fields of an event view that may hold a sentence Floor42 wrote (EventLine.java, CODED), and the catalogue family
 * each is written from: a permission rule, a reason or an incident's description Floor42 wrote itself.
 */
const CODED_FIELDS: Record<string, string> = { basis: 'basis', detail: 'basis', rule: 'rulefor', reason: 'said', description: 'said' }

function codedText(family: string, code: string | null | undefined, params: Record<string, string> | null | undefined, recorded: string): string {
  const key = `${family}.${code}`
  if (!code || !(key in en)) return recorded
  const values: Record<string, string> = { ...(params ?? {}) }
  const word = (w: string) => tr(`basis.word.${w}`, w)
  if (values.decision) values.decision = word(values.decision)
  if (values.status) values.status = word(values.status)
  if (values.target) values.target = values.target.replace(/^[A-Z0-9_]+(?=$| )/, word)
  return t(key as Key, values)
}

/** A denial's reason code (POLICY, DIRECTOR...) in the screen's words. */
export const denialText = (reason: string): string => tr(`denial.${reason}`, reason)

/** The values of a coded field of an event view ("<field>.<name>"), back as the code's own values. */
function codedOf(params: Record<string, string>, field: string): Record<string, string> {
  const own: Record<string, string> = {}
  for (const [k, v] of Object.entries(params)) if (k.startsWith(`${field}.`)) own[k.slice(field.length + 1)] = v
  return own
}

/**
 * The default notes the interface writes into the journal (a reason for closing, abandoning, rejecting). The journal
 * stays language-neutral: they are always sent in English (`defaultNote`) and shown in the screen's language (`noteText`).
 */
export const DEFAULT_NOTES = [
  'board.closeDefault',
  'report.closeDefault',
  'note.abandoned',
  'note.planRejected',
  'note.closedAfterReview',
  'note.lessonRejected',
  'note.lessonRetired',
  'prompt.dismissDefault',
  'prompt.suspendDefault',
  'board.dropDefault',
] as const satisfies readonly Key[]

export type DefaultNote = (typeof DEFAULT_NOTES)[number]

/** The note to record, in English whatever the screen's language. */
export const defaultNote = (key: DefaultNote): string => en[key]

/** A reason field starting with a default note: shown in the screen's language, recorded in English when kept. */
export const noteReason = (key: DefaultNote): { default: string; recorded: string } => ({ default: t(key), recorded: defaultNote(key) })

const RECORDED_NOTES: Record<string, Key> = Object.fromEntries(DEFAULT_NOTES.map((key) => [en[key], key]))

/** A recorded value, in the screen's language when it is one of the interface's own default notes. */
export function noteText(recorded: string): string {
  const key = RECORDED_NOTES[recorded]
  return key ? t(key) : recorded
}

/**
 * A sentence Floor42 computed for a view (a profile fact, a scorecard value), in the screen's language from its code,
 * or as the server wrote it when the catalogue has no entry for that code.
 */
export function phrased(
  prefix: 'fact' | 'scorev' | 'night',
  code: string | null | undefined,
  params: Record<string, string> | null | undefined,
  fallback: string,
): string {
  const key = `${prefix}.${code}`
  return code && key in en ? t(key as Key, params ?? {}) : fallback
}

/** The phrase values an event view carries for a question ("phrase.<name>"), back as the phrase's own values. */
function phraseOf(params: Record<string, string>): Record<string, string> {
  const values: Record<string, string> = {}
  for (const [k, v] of Object.entries(params)) if (k.startsWith('phrase.')) values[k.slice(7)] = v
  return values
}

/**
 * An event's log line in the screen's language, from the code and values the server sends (ADR-0021, lot 3). A code
 * the catalogue does not know (catalogue.test.ts makes sure the server emits none) shows the fallback, else the code
 * itself: nothing is invented.
 */
export function eventText(
  code: string | null | undefined,
  params: Record<string, string> | null | undefined,
  fallback: string = code ?? '',
): string {
  const key = `ev.${code}`
  if (!code || !params || !(key in en || key in identityEn || key in unsafeEn)) return fallback
  const values: Record<string, string> = { ...params }
  for (const field of ['summary', 'reason']) if (values[field]) values[field] = noteText(values[field])
  for (const [field, family] of Object.entries(WORDS)) {
    if (values[field]) values[field] = tr(`${family}.${values[field]}`, values[field].toLowerCase())
  }
  if (params.phrase) values.subject = questionText(params.phrase, phraseOf(params), 'subject', values.subject ?? '')
  for (const [field, family] of Object.entries(CODED_FIELDS)) {
    const coded = params[`${field}Code`]
    if (coded && params[field] !== undefined) values[field] = codedText(family, coded, codedOf(params, field), params[field])
  }
  if (code === 'SessionRiskRaised' || code === 'MissionQuarantined') {
    const words = (family: string, codes: string | undefined) =>
      (codes ?? '').split(/,\s*/).filter(Boolean).map((c) => tr(`${family}.${c}`, c)).join(', ')
    values.level = tr(`risk.level.${params.level}`, params.level ?? '')
    values.reasons = words('risk.reason', params.reasons)
    if (code === 'MissionQuarantined') values.done = words('risk.step', params.done)
  }
  if (code === 'ActionDenied' && params.reason) values.reason = denialText(params.reason)
  const type = code.split('.')[0]
  const tails = (TAILS[type] ?? []).filter(([, shown]) => shown(params)).map(([k]) => t(k, values))
  return t(key as Key, values) + tails.join('')
}
