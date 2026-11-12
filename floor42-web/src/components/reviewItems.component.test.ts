// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import type { ReviewItemsView } from '../api/types'
import { setLang } from '../i18n'
import ReviewItems from './ReviewItems.vue'

const items = (extra: Partial<ReviewItemsView> = {}): ReviewItemsView => ({
  declared: 'VALIDATED_WITH_RESERVATIONS',
  reservations: [{ criterion: 2, criterionText: 'covers upgrade', gap: 'skips the backup', change: 'add the backup step' }],
  notes: [
    { text: 'headings vary', why: 'NO_CRITERION' },
    { text: 'a table would read better', why: 'NOTE' },
  ],
  ...extra,
})

afterEach(() => setLang('en'))

describe('ReviewItems', () => {
  it('shows reservations and notes apart, with why a reservation became a note', () => {
    setLang('en')
    const w = mount(ReviewItems, { props: { items: items() } })
    const reservation = w.find('.reservations')
    expect(reservation.text()).toContain('C2')
    expect(reservation.text()).toContain('covers upgrade')
    expect(reservation.text()).toContain('To change: add the backup step')
    const notes = w.findAll('.note')
    expect(notes).toHaveLength(2)
    expect(notes[0]!.text()).toContain('cites no acceptance criterion')
    expect(notes[1]!.text()).not.toContain('(')
  })

  it('says items it could not read were not read, never that there were none', () => {
    setLang('en')
    const w = mount(ReviewItems, {
      props: { items: items({ reservations: [], notes: [{ text: '', why: 'UNPARSED' }] }) },
    })
    expect(w.find('.unparsed').text()).toContain('could not read')
    expect(w.text()).not.toContain('no reservation and no note')
    expect(w.findAll('.note')).toHaveLength(0)
  })

  it('shows nothing for a review recorded before reservations were tied to criteria', () => {
    const w = mount(ReviewItems, { props: { items: null } })
    expect(w.find('.review-items').exists()).toBe(false)
  })
})
