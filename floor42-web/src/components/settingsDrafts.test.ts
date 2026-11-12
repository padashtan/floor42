import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import type { SettingView } from '../api/types'
import { setLang } from '../i18n'
import { en } from '../i18n/en'
import SettingRow from './SettingRow.vue'
import { prune, refusalText, saveSetting, setDraft, type SettingsDrafts, settingsDrafts } from './settingsDrafts'

beforeAll(() => setLang('en'))

const fresh = (): SettingsDrafts => ({ values: {}, refusals: {} })
const refused = (code = 'INVALID_ARGUMENT', message = 'The default cap is between 0.05 and 1000.0 USD') =>
  new ApiError(400, code, message)

describe('the settings drafts', () => {
  let store: SettingsDrafts
  beforeEach(() => (store = fresh()))

  it('keep a typed value, forget it when it equals the value in force', () => {
    setDraft(store, 'STALL_MINUTES', '10', '5')
    expect(store.values).toEqual({ STALL_MINUTES: '10' })
    setDraft(store, 'STALL_MINUTES', '5', '5')
    expect(store.values).toEqual({})
  })

  it('drop a draft once saved', async () => {
    store.values.STALL_MINUTES = '10'
    const writes: [string, string | null][] = []
    const ok = await saveSetting(store, 'STALL_MINUTES', '10', async (k, v) => {
      writes.push([k, v])
      return null
    })
    expect(ok).toBe(true)
    expect(writes).toEqual([['STALL_MINUTES', '10']])
    expect(store).toEqual(fresh())
  })

  it('keep the draft and phrase the refusal when the daemon refuses the value', async () => {
    store.values.MISSION_BUDGET_USD = 'abc'
    const ok = await saveSetting(store, 'MISSION_BUDGET_USD', 'abc', async () => refused())
    expect(ok).toBe(false)
    expect(store.values.MISSION_BUDGET_USD).toBe('abc')
    expect(store.refusals.MISSION_BUDGET_USD).toBe(en['sr.refused.MISSION_BUDGET_USD'])
    expect(store.refusals.MISSION_BUDGET_USD).not.toContain('INVALID_ARGUMENT')
  })

  it('clear a refusal when the director types again or the save succeeds', async () => {
    store.values.PLAN_LIMIT_THRESHOLD = '2'
    await saveSetting(store, 'PLAN_LIMIT_THRESHOLD', '2', async () => refused())
    setDraft(store, 'PLAN_LIMIT_THRESHOLD', '0.8', '0.9')
    expect(store.refusals).toEqual({})
    await saveSetting(store, 'PLAN_LIMIT_THRESHOLD', '2', async () => refused())
    await saveSetting(store, 'PLAN_LIMIT_THRESHOLD', '0.8', async () => null)
    expect(store).toEqual(fresh())
  })

  it('phrase other refusals with our sentence before the reason, never the raw code', () => {
    expect(refusalText('SHELL_SANDBOX', refused())).toBe(
      en['sr.refused'].replace('{reason}', 'The default cap is between 0.05 and 1000.0 USD'),
    )
    expect(refusalText('STALL_MINUTES', new TypeError('Failed to fetch'))).toBe(
      en['sr.refusedOther'].replace('{reason}', 'Failed to fetch'),
    )
  })

  it('prune the drafts a reload shows in force, keep the others', () => {
    store.values = { STALL_MINUTES: '10', PARALLEL_SESSIONS: '3' }
    prune(store, [{ key: 'STALL_MINUTES', value: '10' } as SettingView, { key: 'PARALLEL_SESSIONS', value: '2' } as SettingView])
    expect(store.values).toEqual({ PARALLEL_SESSIONS: '3' })
  })

  it('live in one store for the page, not in the panel', () => {
    expect(settingsDrafts).toEqual(fresh())
  })
})

describe('a setting row', () => {
  const render = (setting: SettingView, props: Record<string, unknown> = {}) =>
    renderToString(createSSRApp({ render: () => h(SettingRow, { setting, busy: false, ...props }) }))

  it('shows a key this screen does not know by its own name, with a text editor', async () => {
    const html = await render({ key: 'SOMETHING_NEW' as SettingView['key'], value: 'x', defaultValue: 'x', changed: false, options: [] })
    expect(html).toContain('SOMETHING_NEW')
    expect(html).toMatch(/<input[^>]*id="setting-SOMETHING_NEW"[^>]*type="text"/)
    expect(html).not.toContain('type="number"')
  })

  it('shows the draft in the editor and the refusal beside it', async () => {
    const html = await render(
      { key: 'MISSION_BUDGET_USD', value: '5.0', defaultValue: '5.0', changed: false, options: [] },
      { draft: 'abc', refusal: 'Not saved: no.' },
    )
    expect(html).toMatch(/value="abc"/)
    expect(html).toContain('role="alert"')
    expect(html).toContain('Not saved: no.')
  })
})
