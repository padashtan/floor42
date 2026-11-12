// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { WorkspaceView } from '../api/types'
import { setLang } from '../i18n'
import WorkshopActions from './WorkshopActions.vue'
import WorkshopFiles from './WorkshopFiles.vue'

const workspace = (leftOut: string[]): WorkspaceView => ({
  agent: 'chris',
  path: '/w',
  branch: 'floor42/m-1-chris',
  baseCommit: 'a',
  head: 'a',
  changes: [{ path: 'notes.txt', status: 'ADDED', added: 1, removed: 0 }],
  editedByDirector: [],
  leftOut,
})

const mounted = (leftOut: string[]) =>
  mount(WorkshopFiles, { props: { workspace: workspace(leftOut), files: [], path: null, mode: 'diff', busy: false } })

describe('the workshop files', () => {
  it('says which build and cache files an acceptance leaves out', () => {
    setLang('en')
    const hint = mounted(['__pycache__/a.pyc', 'node_modules/x/i.js']).find('.left-out')
    expect(hint.text()).toContain('Left out of an acceptance: 2 build or cache file(s)')
    expect(hint.text()).toContain('__pycache__/a.pyc, node_modules/x/i.js')
    expect(hint.attributes('title')).toBe('__pycache__/a.pyc\nnode_modules/x/i.js')
  })

  it('says nothing when nothing is left out', () => {
    expect(mounted([]).find('.left-out').exists()).toBe(false)
  })
})

describe('the pending changes the director picks', () => {
  const two = (): WorkspaceView => ({
    ...workspace([]),
    changes: [
      { path: 'a.txt', status: 'ADDED', added: 1, removed: 0 },
      { path: 'b.txt', status: 'MODIFIED', added: 2, removed: 1 },
    ],
  })
  const props = (unticked: string[]) => ({ workspace: two(), files: [], path: null, mode: 'diff' as const, busy: false, unticked })

  it('ticks every file by default and unticks the ones left out', () => {
    setLang('en')
    const boxes = mount(WorkshopFiles, { props: props(['b.txt']) }).findAll('input[type=checkbox]')
    expect(boxes.map((b) => (b.element as HTMLInputElement).checked)).toEqual([true, false])
    expect(
      mount(WorkshopFiles, { props: props([]) })
        .findAll('input[type=checkbox]')
        .every((b) => (b.element as HTMLInputElement).checked),
    ).toBe(true)
  })

  it('reports the file left out or put back, and asks to discard one file', async () => {
    const w = mount(WorkshopFiles, { props: props(['b.txt']) })
    await w.findAll('input[type=checkbox]')[0].setValue(false)
    expect(w.emitted('update:unticked')?.[0]).toEqual([['b.txt', 'a.txt']])
    await w.findAll('input[type=checkbox]')[1].setValue(true)
    expect(w.emitted('update:unticked')?.[1]).toEqual([['a.txt']])
    await w.findAll('button.discard')[1].trigger('click')
    expect(w.emitted('discard')).toEqual([['b.txt']])
  })

  it('says how many files go in', () => {
    const w = mount(WorkshopActions, {
      props: {
        mission: 'M-1', workspace: two(), tickets: [], pending: 4, picked: 3, busy: false, atWork: [], accepted: false,
        ticket: null, directive: '', message: 'm', reason: '',
      },
    })
    expect(w.find('.picked-count').text()).toBe('3 of 4 files')
  })
})
