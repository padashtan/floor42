import { beforeEach, describe, expect, it } from 'vitest'
import { setLang, t } from '../i18n'
import { currentTicket, dismissal } from './dismissal'

const ada = { name: 'Ada', stays: false, atWork: false }

describe('dismissal', () => {
  beforeEach(() => setLang('en'))

  it('lets an idle agent go, saying its history stays', () => {
    expect(dismissal(ada, undefined)).toEqual({ hint: t('menu.dismissHint') })
  })

  it('says the ticket in progress goes back to the queue', () => {
    expect(dismissal(ada, { id: 'T2', status: 'IN_PROGRESS' }).hint).toBe(t('menu.dismissHandsBack', { id: 'T2' }))
    expect(dismissal(ada, { id: 'T2', status: 'IN_REVIEW' }).hint).toBe(t('menu.dismissHint'))
  })

  it('refuses an agent at work in a session', () => {
    expect(dismissal({ ...ada, atWork: true }, undefined).refused).toBe(t('menu.dismissAtWork', { name: 'Ada' }))
  })

  it('refuses a chair first, even at work', () => {
    expect(dismissal({ ...ada, stays: true, atWork: true }, undefined).refused).toBe(
      t('menu.dismissNeeded', { name: 'Ada' }),
    )
  })

  it('finds the ticket an agent is on now', () => {
    const tickets = [
      { id: 'T1', assignee: 'ada', status: 'DONE' },
      { id: 'T2', assignee: 'bob', status: 'IN_PROGRESS' },
      { id: 'T3', assignee: 'ada', status: 'IN_REVIEW' },
    ]
    expect(currentTicket('ada', tickets)?.id).toBe('T3')
    expect(currentTicket('eve', tickets)).toBeUndefined()
  })
})
