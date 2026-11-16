// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { t } from '../i18n'
import BoardPanel from './BoardPanel.vue'

const state = vi.hoisted(() => ({ office: { mission: null as unknown, agents: [] as unknown[] } }))
vi.mock('../state/office', () => {
  state.office = reactive(state.office) as typeof state.office
  return { office: state.office, approveTickets: vi.fn(), completeMission: vi.fn(), reviewStaffing: vi.fn() }
})
vi.mock('../api/client', () => ({
  api: {
    held: async () => [],
    report: async () => ({ outputs: [] }),
    ticketsCsvUrl: (m: string) => `/api/missions/${m}/tickets.csv`,
  },
  ApiError: class ApiError extends Error {},
}))

const stubs = Object.fromEntries(
  ['PutOnBoard', 'AbandonMissionButton', 'ArchiveMissionButton', 'MissionAbandoned', 'DeliveryHome', 'DelegationUntil', 'ObjectionsButton', 'TicketDetail', 'MissionGraph'].map((n) => [n, true]),
)

function board(tickets: unknown[]) {
  state.office.mission = { id: 'M-1', title: 'Port it', status: 'IN_PROGRESS', origin: 'SIMULATED', tickets, sessions: [], questions: [] }
  return mount(BoardPanel, { global: { stubs } })
}

describe('the board Jira export', () => {
  it('is a download link to the CSV when the mission has tickets', () => {
    const w = board([{ id: 'T-1', title: 'x', status: 'TODO', dependsOn: [], acceptanceCriteria: [], reviews: [], consultations: [] }])
    const a = w.find('a.jira-export')
    expect(a.attributes('href')).toBe('/api/missions/M-1/tickets.csv')
    expect(a.attributes('download')).toBeDefined()
  })

  it('is disabled with its reason when there is no ticket', () => {
    const b = board([]).find('button.jira-export')
    expect(b.attributes('disabled')).toBeDefined()
    expect(b.attributes('title')).toBe(t('board.exportJiraNone'))
  })
})
