// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { QuestionView } from '../api/types'
import { ApiError } from '../api/client'
import { setLang } from '../i18n'
import QuestionCard from './QuestionCard.vue'

const office = vi.hoisted(() => ({ answer: vi.fn(), state: { mission: null, agents: [] } }))
const askBack = vi.hoisted(() => ({ send: vi.fn() }))
vi.mock('../api/askBack', () => ({ askBackApi: askBack }))
vi.mock('../state/office', () => ({ answer: office.answer, office: office.state }))

const question = (over: Partial<QuestionView> = {}): QuestionView => ({
  id: 'Q-2',
  mission: 'M-1',
  kind: 'OPEN',
  askedBy: 'chris',
  subject: 'My write was refused. What now?',
  context: null,
  options: ['Retry', 'Give up'],
  recommendation: null,
  criticality: 'MEDIUM',
  blocking: true,
  ticket: 'T-1',
  action: null,
  status: 'OPEN',
  answer: null,
  askedAt: '2026-11-02T10:00:00Z',
  phraseCode: null,
  phraseParams: null,
  askAgain: [{ question: 'Q-1', action: 'a1', tool: 'Write', summary: 'notes.txt' }],
  contextCode: null,
  contextParams: null,
  causeCode: null,
  causeParams: null,
  causeBy: null,
  writesAsked: null,
  writesNow: null,
  askBack: { rounds: 0, possible: false, text: null },
  ...over,
})

describe('the ask-again tick box of a question', () => {
  beforeEach(() => {
    setLang('en')
    office.answer.mockReset()
    office.answer.mockResolvedValue(undefined)
  })

  it('sends the exact refusal ticked with the answer', async () => {
    const card = mount(QuestionCard, { props: { q: question() } })
    const box = card.find('.ask-again input[type="checkbox"]')
    expect(card.find('.ask-again').text()).toContain('Ask me again for Write: notes.txt (refused in Q-1)')
    await box.setValue(true)
    await card.findAll('.options button')[0].trigger('click')
    await flushPromises()
    expect(office.answer).toHaveBeenCalledWith('Q-2', 'Retry', 'Q-1')
  })

  it('sends no lift when the box is left unticked, and none is offered without a refusal', async () => {
    const card = mount(QuestionCard, { props: { q: question() } })
    await card.findAll('.options button')[0].trigger('click')
    await flushPromises()
    expect(office.answer).toHaveBeenCalledWith('Q-2', 'Retry', null)
    expect(mount(QuestionCard, { props: { q: question({ askAgain: [] }) } }).find('.ask-again').exists()).toBe(false)
  })
})

describe('the cause of an approval question', () => {
  beforeEach(() => setLang('en'))

  it('names the rule that made the call ask, and who recorded it', () => {
    const q = question({
      kind: 'ACTION_APPROVAL',
      askAgain: [],
      causeCode: 'rule',
      causeParams: { decision: 'ASK', target: 'Bash', agent: '', ticket: '' },
      causeBy: 'COMMITTEE_TABLE',
    })
    expect(mount(QuestionCard, { props: { q } }).text()).toContain(
      'Asked because of the rule Ask once Bash for any agent (committee table)',
    )
  })

  it('claims no cause when the server gives none', () => {
    expect(mount(QuestionCard, { props: { q: question({ askAgain: [] }) } }).text()).not.toContain('Asked because')
  })
})

describe('a ruling whose tables changed since it was asked', () => {
  beforeEach(() => setLang('en'))

  it('says what writing would be now before the director answers', () => {
    const q = question({ kind: 'COMMITTEE_ARBITRATION', askAgain: [], writesAsked: 'ASK', writesNow: 'ALLOW' })
    expect(mount(QuestionCard, { props: { q } }).text()).toContain(
      'Since this question was asked, writes on the implementation would be: allowed',
    )
  })

  it('says nothing when the label still holds', () => {
    const q = question({ kind: 'COMMITTEE_ARBITRATION', askAgain: [], writesAsked: 'ASK', writesNow: 'ASK' })
    expect(mount(QuestionCard, { props: { q } }).text()).not.toContain('Since this question was asked')
  })
})

describe('asking the agent back (ADR-0059)', () => {
  beforeEach(() => {
    setLang('en')
    askBack.send.mockReset()
  })
  const open = question({ options: [], askAgain: [], askBack: { rounds: 1, possible: true, text: null } })

  it('offers the button on an agent question, with its cost, and sends the words', async () => {
    askBack.send.mockResolvedValue(open)
    const card = mount(QuestionCard, { props: { q: open } })
    expect(card.find('[data-ask-back-rounds]').text()).toContain('Asked back so far on this question: 1')
    await card.find('[data-ask-back-open]').trigger('click')
    expect(card.find('[data-ask-back]').text()).toContain('one more paid turn of chris')
    await card.find('[data-ask-back] textarea').setValue('Which colours do you have?')
    await card.find('[data-ask-back]').trigger('submit')
    await flushPromises()
    expect(askBack.send).toHaveBeenCalledWith('Q-2', 'Which colours do you have?')
    expect(card.find('[data-ask-back]').exists()).toBe(false)
  })

  it('offers nothing for an approval, at the cap or once answered', () => {
    for (const q of [
      question({ askBack: { rounds: 0, possible: false, text: null } }),
      question({ askBack: { rounds: 3, possible: false, text: null } }),
    ]) {
      expect(mount(QuestionCard, { props: { q } }).find('[data-ask-back-open]').exists()).toBe(false)
    }
    expect(mount(QuestionCard, { props: { q: question({ askBack: { rounds: 3, possible: false, text: null } }) } }).text()).toContain(
      'answer it or give a directive',
    )
  })

  it('says the session is gone and keeps the other paths open', async () => {
    askBack.send.mockRejectedValue(new ApiError(409, 'SESSION_GONE', 'gone'))
    const card = mount(QuestionCard, { props: { q: open } })
    await card.find('[data-ask-back-open]').trigger('click')
    await card.find('[data-ask-back] textarea').setValue('Why?')
    await card.find('[data-ask-back]').trigger('submit')
    await flushPromises()
    expect(card.find('[data-ask-back-gone]').text()).toContain('no longer waits')
    expect(card.find('.free-answer').exists()).toBe(true)
  })

  it('still shows a question that carries no ask-back state (a recorded one)', () => {
    setLang('en')
    const old: Partial<QuestionView> = { ...question() }
    delete old.askBack
    const w = mount(QuestionCard, { props: { q: old as QuestionView } })
    expect(w.text()).toContain('My write was refused. What now?')
  })
})
