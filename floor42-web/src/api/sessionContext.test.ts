import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'

describe('a session context asked before its first reading', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('answers no reading (204) as null, not as a failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    expect(await api.sessionContext('M-1:abc')).toBeNull()
  })

  it('still refuses an unknown session (404)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"code":"NOT_FOUND","message":"No session"}', { status: 404 })))
    await expect(api.sessionContext('M-0:nobody')).rejects.toMatchObject({ status: 404 })
  })
})
