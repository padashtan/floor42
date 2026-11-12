import { afterEach, describe, expect, it, vi } from 'vitest'
import { optionLabel, setLang } from '../i18n'
import { questionText } from '../i18n/events'
import { pending } from '../state/confirm'
import { directiveMayGo, directiveWarning } from './directiveWarning'

const browser = { missing: 'a desktop browser', step: 'render report.pdf with tools/render.cmd' }

describe('a step the environment prevents', () => {
  afterEach(() => setLang('en'))

  it('warns before a directive, naming what is missing, in both languages', () => {
    setLang('en')
    expect(directiveWarning('T-1', browser)).toBe(
      'The last report of T-1 says the environment lacks a desktop browser, needed to render report.pdf with'
        + ' tools/render.cmd. A directive alone will not provide it: install it, allow an exception, or do the step'
        + ' yourself. Send it back to work anyway?',
    )
    expect(directiveWarning('T-1', { missing: 'the network', step: null })).toContain('lacks the network. A directive')
    expect(directiveWarning('T-1', null)).toBeNull()
    setLang('fr')
    expect(directiveWarning('T-1', browser)).toContain('l’environnement n’a pas a desktop browser')
  })

  it('lets a directive go at once when nothing blocks, and waits for the confirmation otherwise', async () => {
    expect(await directiveMayGo('T-1', null)).toBe(true)
    const asked = directiveMayGo('T-1', browser)
    await vi.waitFor(() => expect(pending.value).not.toBeNull())
    expect(pending.value!.request.message).toContain('a desktop browser')
    pending.value!.settle(null)
    expect(await asked).toBe(false)
  })

  it('reads the question and its options in the screen language', () => {
    setLang('fr')
    const params = { ticket: 'T-1', missing: 'a desktop browser', step: 'render', reviewer: 'bob', findings: 'no PDF' }
    expect(questionText('environmentBlocked', params, 'subject', 'recorded')).toBe(
      'T-1 est bloqué par l’environnement : il y manque a desktop browser',
    )
    expect(questionText('environmentBlockedNoStep', params, 'context', 'recorded')).toContain('Relecteur bob : no PDF')
    expect(optionLabel('I install what is missing')).toBe('J’installe ce qui manque')
  })
})
