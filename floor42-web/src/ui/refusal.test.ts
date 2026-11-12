import { afterAll, describe, expect, it } from 'vitest'
import { setLang } from '../i18n'
import { refusalText } from './refusal'

describe('a refusal on the banner', () => {
  afterAll(() => setLang('en'))

  it('says a known code in the screen language, the code kept', () => {
    setLang('fr')
    expect(refusalText('NAME_TAKEN', 'An agent named Ada already works here')).toBe(
      'NAME_TAKEN: Un agent de ce nom travaille déjà ici : choisissez-en un autre',
    )
    setLang('en')
    for (const code of ['UNKNOWN_MODEL', 'TITLE_TOO_LONG', 'OBJECTIVE_TOO_LONG', 'IDEMPOTENCY_KEY_REUSED', 'INVALID_IDEMPOTENCY_KEY']) {
      expect(refusalText(code, 'daemon text')).not.toContain('daemon text')
    }
  })

  it('keeps the daemon message for a code without a text of its own', () => {
    expect(refusalText('AGENT_AT_WORK', 'A session is still running on M-1 (chris on T-1)')).toBe(
      'AGENT_AT_WORK: A session is still running on M-1 (chris on T-1)',
    )
  })
})
