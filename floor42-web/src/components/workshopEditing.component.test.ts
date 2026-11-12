// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '../api/request'
import { setLang } from '../i18n'
import WorkshopPanel from './WorkshopPanel.vue'

const api = vi.hoisted(() => ({
  workshop: vi.fn(),
  workshopFiles: vi.fn(),
  workshopFile: vi.fn(),
  workshopDiff: vi.fn(),
  editFile: vi.fn(),
}))
const ui = vi.hoisted(() => ({ ask: vi.fn(), office: null as unknown }))
vi.mock('../api/client', async () => ({ api, ApiError: (await import('../api/request')).ApiError }))
vi.mock('../state/confirm', () => ({ ask: ui.ask }))
vi.mock('../state/office', async () => {
  const { reactive } = await import('vue')
  ui.office = reactive({ mission: { id: 'M-1', title: 'T', tickets: [], sessions: [] } })
  return { office: ui.office, onLiveEvent: () => () => {}, workshopCall: async (run: () => unknown) => run() }
})

beforeAll(() => setLang('en'))

const ws = (agent: string) => ({ agent, branch: 'b', baseCommit: 'abcdefgh1', head: 'abcdefgh1', changes: [] })
const view = { workspaces: [ws('ada')], interventions: [] }

beforeEach(() => {
  vi.resetAllMocks()
  api.workshop.mockResolvedValue(view)
  api.workshopFiles.mockResolvedValue(['a.txt', 'b.txt'])
  api.workshopFile.mockImplementation(async (_m: string, _a: string, p: string) => ({ path: p, content: `content of ${p}`, binary: false }))
})

/** Files and viewer stubbed down to what drives the panel: the buttons and what the viewer is shown. */
async function panel() {
  const w = mount(WorkshopPanel, {
    global: {
      stubs: {
        WorkshopActions: true,
        WorkshopChecks: true,
        WorkshopTrace: true,
        WorkshopFiles: {
          emits: ['open', 'new-file'],
          template: `<div><button class="a" @click="$emit('open', 'a.txt', 'file')" /><button class="b" @click="$emit('open', 'b.txt', 'file')" /><button class="new" @click="$emit('new-file')" /></div>`,
        },
        WorkshopViewer: {
          props: ['path', 'mode', 'text', 'draft'],
          emits: ['update:draft', 'edit', 'save'],
          template: `<div class="v" :data-path="path" :data-mode="mode" :data-text="text" :data-draft="draft"><button class="save" @click="$emit('save')" /><button class="type" @click="$emit('update:draft', 'typed')" /><button class="edit" @click="$emit('edit', path)" /></div>`,
        },
      },
    },
  })
  await flushPromises()
  return w
}
const shown = (w: Awaited<ReturnType<typeof panel>>) => ({ ...w.find('.v').attributes() })

it('a new file named like an existing one opens that file for editing, never a blank editor', async () => {
  ui.ask.mockResolvedValue('a.txt')
  const w = await panel()
  await w.find('.new').trigger('click')
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-path': 'a.txt', 'data-mode': 'edit', 'data-text': 'content of a.txt' })
  w.unmount()
})

it('the file opened last is the one shown, whatever order the answers come in', async () => {
  const late: Record<string, (v: unknown) => void> = {}
  api.workshopFile.mockImplementation(
    (_m: string, _a: string, p: string) => new Promise((resolve) => (late[p] = resolve)),
  )
  const w = await panel()
  await w.find('.a').trigger('click')
  await w.find('.b').trigger('click')
  late['b.txt']({ path: 'b.txt', content: 'B', binary: false })
  await flushPromises()
  late['a.txt']({ path: 'a.txt', content: 'A', binary: false })
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-path': 'b.txt', 'data-text': 'B' })
  w.unmount()
})

it('leaving a dirty draft for another file asks first, and keeps the draft on a no', async () => {
  const w = await panel()
  await w.find('.a').trigger('click')
  await flushPromises()
  await w.find('.edit').trigger('click')
  await flushPromises()
  await w.find('.type').trigger('click')
  ui.ask.mockResolvedValueOnce(null)
  await w.find('.b').trigger('click')
  await flushPromises()
  expect(ui.ask).toHaveBeenCalledTimes(1)
  expect(shown(w)).toMatchObject({ 'data-path': 'a.txt', 'data-mode': 'edit' })
  ui.ask.mockResolvedValueOnce(true)
  await w.find('.b').trigger('click')
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-path': 'b.txt', 'data-mode': 'file' })
  w.unmount()
})

it('an Edit started for a file the director already left does not turn the editor on', async () => {
  const late: Record<string, (v: unknown) => void> = {}
  api.workshopFile.mockImplementation(
    (_m: string, _a: string, p: string) => new Promise((resolve) => (late[p] = resolve)),
  )
  const w = await panel()
  await w.find('.a').trigger('click')
  late['a.txt']({ path: 'a.txt', content: 'A', binary: false })
  await flushPromises()
  await w.find('.edit').trigger('click')
  await w.find('.b').trigger('click')
  late['b.txt']({ path: 'b.txt', content: 'B', binary: false })
  await flushPromises()
  late['a.txt']({ path: 'a.txt', content: 'A', binary: false })
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-path': 'b.txt', 'data-mode': 'file' })
  w.unmount()
})

async function editing(w: Awaited<ReturnType<typeof panel>>) {
  await w.find('.a').trigger('click')
  await flushPromises()
  await w.find('.edit').trigger('click')
  await flushPromises()
  await w.find('.type').trigger('click')
}

it('the Edit button pressed while editing keeps the draft', async () => {
  const w = await panel()
  await editing(w)
  await w.find('.edit').trigger('click')
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-mode': 'edit', 'data-draft': 'typed' })
  w.unmount()
})

it('switching agent with a dirty draft asks, and a no keeps the agent and the draft', async () => {
  api.workshop.mockResolvedValue({ workspaces: [ws('ada'), ws('bob')], interventions: [] })
  const w = await panel()
  await editing(w)
  ui.ask.mockResolvedValueOnce(null)
  await w.find('select').setValue('bob')
  await flushPromises()
  expect((w.find('select').element as HTMLSelectElement).value).toBe('ada')
  expect(shown(w)).toMatchObject({ 'data-path': 'a.txt', 'data-mode': 'edit', 'data-draft': 'typed' })
  ui.ask.mockResolvedValueOnce(true)
  await w.find('select').setValue('bob')
  await flushPromises()
  expect((w.find('select').element as HTMLSelectElement).value).toBe('bob')
  w.unmount()
})

it('closing the panel with a dirty draft asks, and a no keeps it open', async () => {
  const w = await panel()
  await editing(w)
  ui.ask.mockResolvedValueOnce(null)
  await w.find('button.close').trigger('click')
  await flushPromises()
  expect(w.emitted('close')).toBeUndefined()
  ui.ask.mockResolvedValueOnce(true)
  await w.find('button.close').trigger('click')
  await flushPromises()
  expect(w.emitted('close')).toHaveLength(1)
  w.unmount()
})

it('a new file is new only when the daemon says it has no such file, whatever the file list holds', async () => {
  api.workshopFiles.mockResolvedValue(['a.txt'])
  api.workshopFile.mockImplementation(async (_m: string, _a: string, p: string) => {
    if (p === 'brand-new.txt') throw new ApiError(404, 'NO_FILE', 'not a file')
    return { path: p, content: `content of ${p}`, binary: false }
  })
  const w = await panel()
  ui.ask.mockResolvedValueOnce('ignored/by-the-list.txt')
  await w.find('.new').trigger('click')
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-path': 'ignored/by-the-list.txt', 'data-mode': 'edit', 'data-text': 'content of ignored/by-the-list.txt' })
  ui.ask.mockResolvedValueOnce('brand-new.txt')
  await w.find('.new').trigger('click')
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-path': 'brand-new.txt', 'data-mode': 'edit' })
  expect(shown(w)['data-text']).toBe('')
  w.unmount()
})

it('a save that answers after the director moved on does not pull the screen back', async () => {
  let done: (v: unknown) => void = () => {}
  api.editFile.mockImplementation(() => new Promise((resolve) => (done = resolve)))
  const w = await panel()
  await editing(w)
  await w.find('.save').trigger('click')
  ui.ask.mockResolvedValue(true)
  await w.find('.b').trigger('click')
  await flushPromises()
  done(view)
  await flushPromises()
  expect(shown(w)).toMatchObject({ 'data-path': 'b.txt' })
  w.unmount()
})
