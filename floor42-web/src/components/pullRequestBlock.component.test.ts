// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PullRequestView } from '../api/types'
import { setLang, t } from '../i18n'
import PullRequestBlock from './PullRequestBlock.vue'

const SHA = '84b0d7cb940063d7ff3d5a95c1a0a3e941bc9da8'
const NEWER = '2222222222222222222222222222222222222222'

const client = vi.hoisted(() => ({
  api: {
    pullRequests: vi.fn(),
    pullRequestHead: vi.fn(),
    mergePullRequest: vi.fn(),
    refreshPullRequestChecks: vi.fn(),
  },
}))
vi.mock('../api/client', async (original) => ({ ...(await original<object>()), api: client.api }))

const pr = (over: Partial<PullRequestView> = {}): PullRequestView => ({
  number: 7,
  url: 'https://github.com/acme/app/pull/7',
  workspace: 'pria',
  branch: 'feature',
  base: 'main',
  remote: 'origin',
  found: false,
  openedAt: '2026-11-17T10:00:00Z',
  checks: [{ name: 'build', bucket: 'pass', state: 'SUCCESS' }],
  checksAt: '2026-11-17T10:05:00Z',
  commit: SHA,
  conclusion: 'GREEN',
  stale: null,
  headNow: null,
  mergedCommit: null,
  mergedAt: null,
  ...over,
})

async function panel(view: PullRequestView) {
  client.api.pullRequests.mockResolvedValue([view])
  const w = mount(PullRequestBlock, { props: { mission: 'M-1', workspace: 'pria', accepted: true } })
  await flushPromises()
  return w
}

const mergeButton = (w: ReturnType<typeof mount>) => w.findAll('button.action').find((b) => b.text() === t('pr.merge'))!

beforeAll(() => setLang('en'))
beforeEach(() => vi.clearAllMocks())

describe('the delivery panel of a pull request (ADR-0084)', () => {
  it('shows the checks with the commit they belong to', async () => {
    const w = await panel(pr())
    expect(w.text()).toContain(t('pr.commit', { commit: SHA.slice(0, 8) }))
    expect(w.text()).toContain('build')
  })

  it('lets a green current commit be merged, at that commit', async () => {
    client.api.mergePullRequest.mockResolvedValue(pr({ mergedCommit: SHA, mergedAt: '2026-11-17T10:06:00Z' }))
    const w = await panel(pr())
    const button = mergeButton(w)
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    await flushPromises()
    expect(client.api.mergePullRequest).toHaveBeenCalledWith('M-1', 7, { commit: SHA })
  })

  it('keeps the merge shut, with the reason, when a check is not green or nothing was read', async () => {
    const failed = await panel(pr({ conclusion: 'FAILED' }))
    expect(mergeButton(failed).attributes('disabled')).toBeDefined()
    expect(mergeButton(failed).attributes('title')).toBe(t('pr.whyNotGreen'))
    const unread = await panel(pr({ commit: null, conclusion: null, checks: [], checksAt: null }))
    expect(mergeButton(unread).attributes('title')).toBe(t('pr.whyNoEvidence'))
  })

  it('says the evidence is stale when the branch moved, and shuts the merge', async () => {
    client.api.pullRequestHead.mockResolvedValue(pr({ stale: true, headNow: NEWER }))
    const w = await panel(pr())
    await w.findAll('button.link').find((b) => b.text() === t('pr.checkHead'))!.trigger('click')
    await flushPromises()
    expect(w.find('.pr-stale').text()).toBe(t('pr.stale', { head: NEWER.slice(0, 8), commit: SHA.slice(0, 8) }))
    expect(mergeButton(w).attributes('disabled')).toBeDefined()
    expect(mergeButton(w).attributes('title')).toBe(t('pr.whyStale'))
  })

  it('shows a merged pull request without a merge button, and a refusal as it came', async () => {
    const merged = await panel(pr({ mergedCommit: SHA, mergedAt: '2026-11-17T10:06:00Z' }))
    expect(merged.text()).toContain(t('pr.merged', { commit: SHA.slice(0, 8) }))
    expect(merged.findAll('button.action').some((b) => b.text() === t('pr.merge'))).toBe(false)

    const { ApiError } = await import('../api/client')
    client.api.mergePullRequest.mockRejectedValue(new ApiError(409, 'CHECKS_NOT_GREEN', 'not green'))
    const w = await panel(pr())
    await mergeButton(w).trigger('click')
    await flushPromises()
    expect(w.find('.warn').text()).toContain('CHECKS_NOT_GREEN')
  })

  it('has both catalogues for every text it uses', async () => {
    const { setLang: set } = await import('../i18n')
    for (const lang of ['en', 'fr'] as const) {
      set(lang)
      for (const key of ['pr.commit', 'pr.stale', 'pr.checkHead', 'pr.merge', 'pr.whyNotGreen', 'pr.whyStale', 'pr.merged']) {
        expect(t(key as never, { commit: 'a', head: 'b' })).not.toBe(key)
      }
    }
    set('en')
  })
})
