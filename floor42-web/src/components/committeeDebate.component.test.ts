// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it } from 'vitest'
import type { CommitteeView, ContributionView } from '../api/types'
import { setLang } from '../i18n'
import CommitteeDebate from './CommitteeDebate.vue'

beforeAll(() => setLang('en'))

const say = (member: string, round: number, kind: ContributionView['kind'] = 'ANALYSIS') =>
  ({ member, role: 'architect', round, kind, text: member, proposal: `by ${member}`, position: null, criticalObjection: null, byDirector: false }) as ContributionView

describe('the committee debate reads newest first', () => {
  it('shows the decision record, then the latest round, down to the first', () => {
    const committee = { contributions: [say('ana', 1), say('bob', 1), say('ana', 2), say('geoffrey', 3, 'SYNTHESIS')] } as unknown as CommitteeView
    const w = mount(CommitteeDebate, { props: { committee } })
    const heads = w.findAll('.round > .sub').map((n) => n.text())
    expect(heads).toHaveLength(3)
    expect(heads[0]).toContain('geoffrey')
    const members = w.findAll('.contribution .who b').map((n) => n.text())
    expect(members).toEqual(['ana', 'ana', 'bob'])
  })
})
