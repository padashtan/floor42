// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it } from 'vitest'
import { setLang, t } from '../i18n'
import { blankTask } from '../ui/workflowTaskEdit'
import WorkflowTaskDialog from './WorkflowTaskDialog.vue'

beforeAll(() => setLang('en'))

const open = (production = true) => mount(WorkflowTaskDialog, { props: { task: blankTask(), adding: true, production }, attachTo: document.body })
const fill = async (w: ReturnType<typeof open>) => {
  await w.get('#wf-task-title').setValue('Research')
  await w.get('#wf-task-does').setValue('Look it up')
  await w.get('#wf-task-why').setValue('We need the facts')
  await w.get('#wf-task-handsOver').setValue('A report')
}
const apply = (w: ReturnType<typeof open>) => w.findAll('button').find((b) => b.text() === t('workflows.chk.apply'))!

describe('the task dialog (ADR-0061)', () => {
  it('refuses an empty task and a hidden character under the field, and applies a valid one', async () => {
    const w = open()
    expect(apply(w).attributes('disabled')).toBeDefined()
    await fill(w)
    expect(apply(w).attributes('disabled')).toBeUndefined()
    await w.get('#wf-task-why').setValue('a​b')
    expect(w.get('[data-problem="why"]').text()).toContain('2')
    expect(apply(w).attributes('disabled')).toBeDefined()
    await w.get('#wf-task-why').setValue('We need the facts')
    await apply(w).trigger('click')
    expect(w.emitted('apply')?.[0][0]).toMatchObject({ title: 'Research', slot: 'beforeAcceptance', runBy: 'researcher', reviewed: false })
  })

  it('moves the task to the other slot, and a developer task is applied reviewed', async () => {
    const w = open()
    await fill(w)
    await w.get('input[value="beforeProduction"]').setValue(true)
    await w.get('#wf-task-runner').setValue('developer')
    await apply(w).trigger('click')
    expect(w.emitted('apply')?.[0][0]).toMatchObject({ slot: 'beforeProduction', runBy: 'developer', reviewed: true })
  })

  it('offers before production only while the workflow produces', () => {
    expect(open(false).get('input[value="beforeProduction"]').attributes('disabled')).toBeDefined()
  })
})
