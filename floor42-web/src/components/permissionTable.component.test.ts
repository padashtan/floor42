// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ToolSettingView } from '../api/types'
import { setLang } from '../i18n'
import PermissionTable from './PermissionTable.vue'

const office = vi.hoisted(() => ({
  state: { mission: { id: 'M-7', origin: 'REAL', committees: [{ number: 1 }] }, toolSettings: [] as unknown[] },
}))
const setToolDecision = vi.hoisted(() => vi.fn())
vi.mock('../state/office', () => ({ office: office.state, forgetApproval: vi.fn(), setToolDecision }))

const write: ToolSettingView = {
  tool: 'Write',
  category: 'WRITE',
  decision: 'DENY',
  source: 'your setting',
  warning: null,
  remembered: [],
  lineBy: 'COMMITTEE_TABLE',
  rules: [
    { code: 'ruleTicket', params: { decision: 'ASK', target: 'Write', agent: '', ticket: 'T-2' }, by: 'FLOOR42' },
    { code: 'ruleAgent', params: { decision: 'DENY', target: 'WRITE', agent: 'darius', ticket: '' }, by: 'FLOOR42' },
  ],
}

describe('the mission permission table', () => {
  beforeEach(() => setLang('en'))

  it('lists every rule beyond the line with who recorded it, and says what a committee table is', () => {
    office.state.toolSettings = [write]
    const text = mount(PermissionTable).text()
    expect(text).toContain('committee table')
    expect(text).toContain('Rule beyond this line: Ask once Write for any agent on T-2 (set by Floor42)')
    expect(text).toContain('Rule beyond this line: Forbidden WRITE for darius (set by Floor42)')
    expect(text).toContain('Committee table: read only, independent of your defaults.')
  })

  it('names the director as the author of a line they set', () => {
    office.state.toolSettings = [{ ...write, lineBy: 'DIRECTOR', rules: [] }]
    const text = mount(PermissionTable).text()
    expect(text).toContain('your setting')
    expect(text).not.toContain('Rule beyond this line')
  })

  it('re-applies the chosen setting when a ticket line outranks it, and says so', async () => {
    office.state.toolSettings = [write]
    setToolDecision.mockReset()
    const table = mount(PermissionTable)
    expect(table.text()).toContain('Click your choice again')
    await table.findAll('button.radio.on')[0].trigger('click')
    expect(setToolDecision).toHaveBeenCalledWith('Write', 'DENY')
  })

  it('ignores a click on a setting the director chose when no ticket line outranks it', async () => {
    office.state.toolSettings = [{ ...write, rules: [], lineBy: 'DIRECTOR' }]
    setToolDecision.mockReset()
    const table = mount(PermissionTable)
    await table.findAll('button.radio.on')[0].trigger('click')
    expect(setToolDecision).not.toHaveBeenCalled()
    expect(table.text()).not.toContain('Click your choice again')
    expect(table.text()).not.toContain('make it yours')
  })

  it('lets the director make a line Floor42 set their own by clicking it', async () => {
    office.state.toolSettings = [{ ...write, rules: [], lineBy: 'COMMITTEE_TABLE' }]
    setToolDecision.mockReset()
    const table = mount(PermissionTable)
    expect(table.text()).toContain('click it to make it yours')
    await table.findAll('button.radio.on')[0].trigger('click')
    expect(setToolDecision).toHaveBeenCalledWith('Write', 'DENY')
  })
})
