import { describe, expect, it } from 'vitest'
import { chainText, decodeForDisplay, missionsOf, refusalText } from './identities'

const claims = {
  iss: 'http://kc/realms/floor42',
  azp: 'floor42-agent-ada',
  act: { client_id: 'floor42-agent-ada' },
  floor42: { launch: 'L1', mission: 'M-3', ticket: 'T-7' },
}

describe('decodeForDisplay', () => {
  it('keeps 8 characters of the signature and "..."', () => {
    expect(decodeForDisplay({ alg: 'RS256' }, claims, 'abcdefghijklmnop').signature).toBe('abcdefgh...')
    expect(decodeForDisplay({}, claims, 'abcdefgh...').signature).toBe('abcdefgh...')
  })

  it('removes anything shaped like a token from the claims', () => {
    const shown = decodeForDisplay({}, { note: 'x eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln y' }, 'abcdefgh...')
    expect(JSON.stringify(shown)).not.toMatch(/eyJ[\w-]+\.[\w-]+\.[\w-]+/)
  })

  it('marks the simulated issuer', () => {
    expect(decodeForDisplay({}, { ...claims, iss: 'urn:floor42:simulated' }, 'abcdefgh...').simulated).toBe(true)
    expect(decodeForDisplay({}, claims, 'abcdefgh...').simulated).toBe(false)
  })
})

describe('chainText', () => {
  const token = decodeForDisplay({}, claims, 'abcdefgh...')
  it('says in English who acts for whom', () => {
    expect(chainText(token, 'en')).toBe('ada acts for the director on M-3/T-7')
  })
  it('says it in French', () => {
    expect(chainText(token, 'fr')).toBe('ada agit pour le directeur sur M-3/T-7')
  })
})

describe('refusalText', () => {
  it('explains a known code in both languages and passes a new one through', () => {
    expect(refusalText('WRONG_PROCESS', 'en')).toContain('process')
    expect(refusalText('WRONG_PROCESS', 'fr')).toContain('processus')
    expect(refusalText('SOMETHING_NEW', 'en')).toBe('SOMETHING_NEW')
  })
})

describe('missionsOf', () => {
  it('lists each mission once', () => {
    expect(missionsOf([{ claims }, { claims }, { claims: {} }])).toEqual(['M-3'])
  })
})
