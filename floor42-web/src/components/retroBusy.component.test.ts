// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it } from 'vitest'
import { ref } from 'vue'
import type { MissionView } from '../api/types'
import { setLang } from '../i18n'
import { MEETING_BUSY } from '../ui/meetingBusy'
import RetroSection from './RetroSection.vue'

beforeAll(() => setLang('en'))

const retro = {
  concluded: false,
  chair: 'Galen',
  participants: ['Galen'],
  pending: [],
  contributions: [],
  conclusions: [],
  lessons: [],
  facts: '',
  reason: null,
} as unknown as NonNullable<MissionView['retro']>

describe('the retrospective close button shares the meeting room busy flag', () => {
  const closeButton = (busy: boolean) =>
    mount(RetroSection, {
      props: { retro, missionId: '' },
      global: { provide: { [MEETING_BUSY as symbol]: ref(busy) } },
    }).find('.retro-wait button.action')

  it('is disabled while another action of the room is running', () => {
    expect(closeButton(true).attributes('disabled')).toBeDefined()
  })

  it('is enabled when nothing is running', () => {
    expect(closeButton(false).attributes('disabled')).toBeUndefined()
  })
})
