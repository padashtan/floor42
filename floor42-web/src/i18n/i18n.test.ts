import { describe, expect, it } from 'vitest'
import { en } from './en'
import { fr } from './fr'
import { setLang, t, tr } from './index'

describe('i18n', () => {
  it('fills placeholders in the chosen language', () => {
    setLang('en')
    expect(t('top.onStaff', { count: 3 })).toBe('3 on staff')
    setLang('fr')
    expect(t('top.onStaff', { count: 3 })).toBe('3 dans l’équipe')
  })

  it('leaves an unknown placeholder visible rather than inventing a value', () => {
    setLang('en')
    expect(t('settings.default')).toBe('default: {value}')
  })

  it('falls back to the given text for a key built at run time that the catalogue does not know', () => {
    setLang('fr')
    expect(tr('status.WORKING', 'working')).toBe(fr['status.WORKING'])
    expect(tr('status.SOMETHING_NEW', 'something new')).toBe('something new')
  })

  it('keeps the same placeholders in both catalogues', () => {
    const names = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()
    for (const key of Object.keys(en) as (keyof typeof en)[]) expect(names(fr[key]), key).toEqual(names(en[key]))
  })
})
