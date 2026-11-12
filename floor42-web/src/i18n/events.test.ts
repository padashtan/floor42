import { afterEach, describe, expect, it } from 'vitest'
import { setLang } from './index'
import contexts from './approval-contexts.json'
import bases from './decision-bases.json'
import { basisText, DEFAULT_NOTES, defaultNote, eventText, noteReason, noteText, phrased, questionContext, questionText } from './events'

afterEach(() => setLang('en'))

/**
 * The approval contexts as the golden journals recorded them, with the code and values the server's question view
 * gives for each (written by EventCatalogueContractTest): the screen's sentences are checked against the server's own.
 */
describe('an approval context written from its code', () => {
  const samples = Object.entries(contexts as Record<string, { context: string; params: Record<string, string> }>)
  const question = (code: string, s: { context: string; params: Record<string, string> }) => ({
    context: s.context,
    contextCode: code,
    contextParams: s.params,
    phraseCode: 'approval',
    phraseParams: {},
  })

  it('covers the four scopes the server writes', () => {
    expect(samples.map(([code]) => code).sort()).toEqual(['approvalAskAlways', 'approvalCommand', 'approvalEdited', 'approvalTool'])
  })

  it.each(samples)('%s is, in English, the server sentence to the letter', (code, s) => {
    expect(questionContext(question(code, s))).toBe(s.context)
  })

  it.each(samples)('%s is, in French, French after the recorded summary', (code, s) => {
    setLang('fr')
    const shown = questionContext(question(code, s))
    expect(shown.startsWith(s.params.summary + '\n')).toBe(true)
    expect(shown).toContain('Votre réponse vaut pour')
    expect(shown).not.toMatch(/answer|applies|this call|request from|rest of this|workshop|setting/)
  })

  it('a context without a code is shown as recorded', () => {
    setLang('fr')
    const q = { context: 'an agent wrote this', contextCode: null, contextParams: null, phraseCode: 'approval', phraseParams: {} }
    expect(questionContext(q)).toBe('an agent wrote this')
  })
})

/**
 * The bases of decisions as the golden journals recorded them, with the code and values the server reads in each
 * (decision-bases.json, written by EventCatalogueContractTest): the feed and the execution card write them from the code.
 */
describe('a decision basis written from its code', () => {
  const { uncoded, fields, ...coded } = bases as unknown as { uncoded: string[]; fields: unknown } & Record<string, { text: string; params: Record<string, string> }>
  const samples = Object.entries(coded as Record<string, { text: string; params: Record<string, string> }>)
  const ENGLISH = /answered|allowed|denied|already|exact command|request from|always available|no rule|default deny|rule:|for any agent|mission is|cancelled|neither|reviewing/

  it('covers the bases the golden journals hold, the director answering among them', () => {
    expect(samples.map(([code]) => code)).toEqual(expect.arrayContaining(['directorAllowed', 'directorDenied', 'alreadyAllowedCommand']))
    expect(uncoded.length).toBeGreaterThan(0)
  })

  it.each(samples)('%s is, in English, the server sentence to the letter', (code, s) => {
    expect(basisText(code, s.params, s.text)).toBe(s.text)
  })

  it.each(samples)('%s is French on a French screen, in the execution card and in the feed', (code, s) => {
    setLang('fr')
    const shown = basisText(code, s.params, s.text)
    expect(shown).not.toMatch(ENGLISH)
    const fieldParams = Object.fromEntries(Object.entries(s.params).map(([k, v]) => [`detail.${k}`, v]))
    const line = eventText('ActionDenied', { call: "chris Bash 'git push'", reason: 'DIRECTOR', detail: s.text, detailCode: code, ...fieldParams }, 'x')
    expect(line).toBe(`Floor42 refuse chris Bash 'git push' [DIRECTEUR] ${shown}`)
    const authorized = eventText('ActionAuthorized', { call: 'chris Read', basis: s.text, basisCode: code, ...Object.fromEntries(Object.entries(s.params).map(([k, v]) => [`basis.${k}`, v])) }, 'x')
    expect(authorized).not.toMatch(ENGLISH)
  })

  // A rule, a reason or an incident's description Floor42 wrote, in the lines that carry them.
  const LINES: Record<string, [string, Record<string, string>]> = {
    rule: ['PermissionRuleAdded', { mission: 'M-1' }],
    reason: ['MissionSuspended', { mission: 'M-1' }],
    description: ['IncidentRaised', { incident: 'I-1', criticality: 'CRITICAL' }],
  }
  const fieldSamples = Object.entries(fields as Record<string, Record<string, { text: string; params: Record<string, string> }>>).flatMap(
    ([field, codes]) => Object.entries(codes).map(([code, s]) => [field, code, s] as const),
  )
  const line = (field: string, code: string, s: { text: string; params: Record<string, string> }) => {
    const [event, base] = LINES[field]
    const own = Object.fromEntries(Object.entries(s.params).map(([k, v]) => [`${field}.${k}`, v]))
    return eventText(event, { ...base, [field]: s.text, [`${field}Code`]: code, ...own }, 'x')
  }

  it.each(fieldSamples)('the %s %s is, in English, the line the server writes', (field, code, s) => {
    const [event, base] = LINES[field]
    expect(line(field, code, s)).toBe(eventText(event, { ...base, [field]: s.text }, 'x'))
  })

  it.each(fieldSamples)('the %s %s is French on a French screen', (field, code, s) => {
    setLang('fr')
    expect(line(field, code, s)).not.toMatch(/for any agent|for chris| on T-|unauthorized execution|before every turn|sent back|mission cancelled|without an Floor42/)
  })

  it('a basis Floor42 did not write is shown as recorded', () => {
    setLang('fr')
    for (const text of uncoded) expect(basisText(null, null, text)).toBe(text)
  })
})

describe('the default notes the interface records', () => {
  const sources = import.meta.glob('../**/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

  it('are English whatever the screen language, so the journal stays language-neutral', () => {
    setLang('fr')
    expect(defaultNote('note.abandoned')).toBe('Abandoned by the director from the desk')
    expect(defaultNote('report.closeDefault')).toBe('Delivery accepted by the director from the end-of-mission report')
    expect(noteReason('prompt.dismissDefault')).toEqual({ default: 'pas nécessaire pour l’instant', recorded: 'not needed now' })
  })

  // t('key'), tt("key"), t(`key`): any translation function, any quote.
  const literal = (key: string) => key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const translates = (key: string) => new RegExp(`\\bt{1,2}\\(\\s*['"\`]${literal(key)}['"\`]`)

  it('are never sent in the screen language by a component', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(50)
    const translated = Object.entries(sources).flatMap(([file, text]) =>
      DEFAULT_NOTES.filter((key) => translates(key).test(text)).map((key) => `${file}: ${key}`),
    )
    expect(translated).toEqual([])
  })

  it('the guard finds every way of calling the catalogue', () => {
    for (const call of [`t('note.abandoned')`, `tt('note.abandoned')`, `t("note.abandoned")`, 'tt( `note.abandoned`)']) {
      expect(translates('note.abandoned').test(call), call).toBe(true)
    }
    expect(translates('note.abandoned').test(`defaultNote('note.abandoned')`)).toBe(false)
  })

  it('are shown in the screen language once recorded', () => {
    setLang('fr')
    for (const key of DEFAULT_NOTES) expect(noteText(defaultNote(key))).not.toBe(defaultNote(key))
  })

  it('the default closing note is shown in French, a note typed by the director as typed', () => {
    setLang('fr')
    expect(noteText('Delivery accepted by the director from the board')).toBe('Livraison acceptée par le directeur depuis le tableau')
    expect(noteText('Accepted, thanks')).toBe('Accepted, thanks')
    expect(eventText('MissionCompleted', { mission: 'M-2', summary: 'Delivery accepted by the director from the board' }, 'x')).toContain(
      'Livraison acceptée',
    )
  })
})

describe('event lines', () => {
  it('are written in the screen language from the server code and values', () => {
    setLang('fr')
    expect(eventText('TicketReviewed', { reviewer: 'darius', ticket: 'T-4', verdict: 'REJECTED' }, 'x')).toContain(
      'darius a relu T-4',
    )
  })

  it('read in English as the server wrote them', () => {
    expect(
      eventText('AgentSessionEnded.ended.cost', { short: 'ab12cd34', cost: '0.0021' }, 'server line'),
    ).toBe('Session ab12cd34 ended · cost 0.0021 USD (estimated)')
  })

  it('carry their optional tails only when the event says so', () => {
    expect(
      eventText('CommitteeArbitrated.adopted', { mission: 'M-3', overCriticalObjection: 'true', implement: 'false' }, 'x'),
    ).toBe("Director adopted the committee's decision on M-3 despite a standing critical objection")
  })

  it('say who opened a pull request and what GitHub reports on it', () => {
    setLang('fr')
    expect(
      eventText(
        'PullRequestOpened.found',
        { number: '7', mission: 'M-3', branch: 'floor42/m-3-chris', base: 'main', url: 'https://github.com/a/b/pull/7' },
        'x',
      ),
    ).toBe('Floor42 a trouvé la pull request n°7 pour M-3 : floor42/m-3-chris vers main (https://github.com/a/b/pull/7)')
    setLang('en')
    expect(eventText('PullRequestCheckState.failed', { number: '7', failed: '1', count: '3' }, 'x')).toBe(
      'Checks of pull request #7 on GitHub: 1 of 3 failed',
    )
  })

  it('fall back to the server line for a code the catalogue does not know', () => {
    expect(eventText('CommitteeAdvanced.closed', { mission: 'M-3' }, 'Committee of M-3: CLOSED')).toBe(
      'Committee of M-3: CLOSED',
    )
    expect(eventText(null, null, 'old line')).toBe('old line')
  })
})

describe('question texts', () => {
  it('are written from the phrase Floor42 kept, else shown as recorded', () => {
    setLang('fr')
    expect(questionText('approval', { agent: 'chris', tool: 'Bash' }, 'subject', 'Allow chris to use Bash?')).toBe(
      'Autoriser chris à utiliser Bash ?',
    )
    expect(questionText('approval', { agent: 'chris', tool: 'Bash' }, 'context', 'git status')).toBe('git status')
    expect(questionText(null, null, 'subject', 'Which branch?')).toBe('Which branch?')
  })

  it('also translate the log line of the question', () => {
    setLang('fr')
    expect(
      eventText(
        'QuestionAsked.blocking',
        { question: 'Q-1', subject: 'Allow chris to use Bash?', phrase: 'approval', 'phrase.agent': 'chris', 'phrase.tool': 'Bash' },
        'x',
      ),
    ).toBe('Question bloquante Q-1 pour le directeur : Autoriser chris à utiliser Bash ?')
  })
})

describe('computed sentences', () => {
  it('write a profile fact or a scorecard value in the screen language, else as the server wrote it', () => {
    setLang('fr')
    expect(phrased('fact', 'failed', { failed: '1', sessions: '4' }, 'x')).toBe('1 session(s) en échec sur 4')
    expect(phrased('scorev', 'cost.budget', { usd: '0.1200', budget: '2.0000' }, 'x')).toBe('0.1200 USD sur 2.0000 USD')
    expect(phrased('scorev', null, null, 'T-1 validated')).toBe('T-1 validated')
  })
})

describe('session risk lines', () => {
  const raised = { agent: 'chris', ticket: 'T-1', level: 'QUARANTINE', reasons: 'POLICY_DENIED, BURST' }
  const quarantined = { ...raised, done: 'REVOKED, STOPPED' }

  it('say the level, the reasons and the steps in words, in English', () => {
    expect(eventText('SessionRiskRaised', raised, 'x')).toBe(
      'chris’s session on T-1 is at risk level quarantine: an action its permissions refuse, three refusals in five minutes',
    )
    expect(eventText('MissionQuarantined', quarantined, 'x')).toContain('steps taken: delegation revoked, session stopped')
    expect(eventText('MissionQuarantined', { ...quarantined, done: 'NONE' }, 'x')).toContain('steps taken: nothing to do')
  })

  it('say them in French, with no code left', () => {
    setLang('fr')
    const line = eventText('MissionQuarantined', quarantined, 'x')
    expect(line).toContain('niveau de risque quarantaine')
    expect(line).toContain('trois refus en cinq minutes')
    expect(line).toContain('délégation révoquée, session arrêtée')
    expect(line).not.toMatch(/BURST|REVOKED|STOPPED|POLICY_DENIED/)
  })

  it('keep a code the catalogue does not know', () => {
    expect(eventText('SessionRiskRaised', { ...raised, reasons: 'NEW_REASON' }, 'x')).toContain(': NEW_REASON')
  })
})