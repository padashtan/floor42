// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ approve: vi.fn(async () => ({})) }))
vi.mock('../api/client', async (actual) => ({ ...(await actual<typeof import('../api/client')>()), api }))

import { approvePlan } from './office'

/** Pilot S2: the resources ticked at approval reach the server (they were dropped between the panel and the call). */
describe('approvePlan', () => {
  it('sends the ticked broker resources, and no resources field when none is ticked', async () => {
    await approvePlan('M-1', 1, false, ['demo'])
    expect(api.approve).toHaveBeenLastCalledWith('M-1', { budgetUsd: 1, noCap: null, resources: ['demo'] })
    await approvePlan('M-1', 1)
    expect(api.approve).toHaveBeenLastCalledWith('M-1', { budgetUsd: 1, noCap: null })
  })
})
