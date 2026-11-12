import { describe, expect, it } from 'vitest'
import { setLang } from '../i18n'
import { askAgainLabel, waitingLabel } from './waiting'

describe('what waits for the director', () => {
  it('names the question that holds a ticket, in both languages', () => {
    setLang('en')
    expect(waitingLabel({ waitingFor: 'Q-5' })).toBe('waiting for your answer (Q-5)')
    expect(waitingLabel({ waitingFor: null })).toBeNull()
    setLang('fr')
    expect(waitingLabel({ waitingFor: 'Q-5' })).toBe('attend votre réponse (Q-5)')
    setLang('en')
  })

  it('names the exact refused call the director may be asked again for', () => {
    setLang('en')
    expect(askAgainLabel({ question: 'Q-1', action: 'a1', tool: 'Write', summary: 'notes.txt' })).toBe(
      'Ask me again for Write: notes.txt (refused in Q-1)',
    )
  })
})
