import { beforeEach, describe, expect, it } from 'vitest'
import { setLang, t } from '../i18n'
import { due, localDay, lineText, markShown, STANDUP_KEY } from './standup'

const store = () => {
  const m = new Map<string, string>()
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) }
}
const at = (h: number, day = 16) => new Date(2026, 10, day, h, 5)

describe('when the standup opens by itself', () => {
  it('not before 09:00, then once a day', () => {
    const s = store()
    expect(due(at(8), s)).toBe(false)
    expect(due(at(9), s)).toBe(true)
    expect(markShown(at(9), s)).toBe(true)
    expect(s.getItem(STANDUP_KEY)).toBe(localDay(at(9)))
    expect(due(at(14), s)).toBe(false)
    expect(due(at(9, 17), s)).toBe(true)
  })

  it('opens at each load when the browser keeps nothing, and says it could not keep', () => {
    expect(due(at(10), null)).toBe(true)
    expect(markShown(at(10), null)).toBe(false)
    const broken = {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    }
    expect(due(at(10), broken)).toBe(true)
    expect(markShown(at(10), broken)).toBe(false)
  })
})

describe('a line reads in the page language', () => {
  beforeEach(() => setLang('en'))

  it('fills the code from its fields, in English and in French', () => {
    const l = { code: 'COMPLETED', mission: 'M-1', ticket: 'T-1', text: 'Add the parser', count: null }
    expect(lineText(l, t)).toBe('Completed: T-1 Add the parser')
    setLang('fr')
    expect(lineText(l, t)).toBe('Terminé : T-1 Add the parser')
    expect(lineText({ code: 'SESSIONS', mission: null, ticket: null, text: null, count: 3 }, t)).toBe('Sessions lancées : 3')
  })

  it('never shows the ticket gap of a line without a ticket, and shows an unknown code as its code', () => {
    expect(lineText({ code: 'QUESTION', mission: null, ticket: null, text: 'May I run it', count: null }, t)).toBe(
      'Waiting for your answer: May I run it',
    )
    expect(lineText({ code: 'NEW_THING', mission: null, ticket: null, text: null, count: null }, t)).toBe('Recorded: NEW_THING')
  })
})
