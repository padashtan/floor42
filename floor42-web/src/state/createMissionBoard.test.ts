// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ createMission: vi.fn(async () => ({ id: 'M-17' })) }))
vi.mock('../api/client', async (actual) => ({ ...(await actual<typeof import('../api/client')>()), api }))

import { BOARD_EMPTY, boardChoice, keepBoardChoice } from '../ui/recent'
import { createMission, office } from './office'

/** Pilot, M-17: a board left empty after an archive hid every mission created afterwards. */
describe('createMission', () => {
  it('puts the new mission on the board, even over a board left empty', async () => {
    keepBoardChoice(BOARD_EMPTY)
    office.boardChoice = BOARD_EMPTY
    await createMission('Broker demo', 'Read the demo resource', 'C:/repo')
    expect(office.boardChoice).toBe('M-17')
    expect(boardChoice()).toBe('M-17')
  })
})
