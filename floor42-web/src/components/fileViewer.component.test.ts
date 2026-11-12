// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import type { DeliveredFile } from '../api/types'
import { setLang } from '../i18n'
import FileViewerPanel from './FileViewerPanel.vue'

const text = vi.hoisted(() => ({ value: '' }))
const viewable = vi.hoisted(() => ({ value: true }))
/** A file whose text answers only when released: the slow read of an earlier file. */
const slow = vi.hoisted(() => ({ path: '', release: () => {}, gate: Promise.resolve() }))
vi.mock('../api/viewedFiles', async (original) => ({
  ...(await original<typeof import('../api/viewedFiles')>()),
  fileText: async (_m: string, source: { path?: string; ref?: { path: string } }) => {
    if ((source.path ?? source.ref?.path) === slow.path) {
      await slow.gate
      return 'OLD FILE TEXT'
    }
    return text.value
  },
  fileCheck: async () => ({ viewable: viewable.value, type: null, code: viewable.value ? null : 'NOT_VIEWABLE', message: null }),
  ownFileEntry: async (_m: string, path: string) => ({
    file: { path, from: null, status: '?', size: 12, commit: null, committed: false, ignored: false, link: false, kind: 'IMAGE' },
    maxViewText: 1024,
    maxViewBytes: 4096,
  }),
}))

beforeAll(() => setLang('en'))

const caps = { maxViewText: 1024, maxViewBytes: 4096 }
const entry = (over: Partial<DeliveredFile>): DeliveredFile => ({
  path: 'notes.md',
  from: null,
  status: '?',
  size: 10,
  commit: null,
  committed: false,
  ignored: false,
  link: false,
  kind: 'TEXT',
  ...over,
})

async function show(file: DeliveredFile) {
  const w = mount(FileViewerPanel, {
    props: { file: { mission: 'M-1', agent: 'dev-1', path: file.path, committed: file.committed, file, view: caps } },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}

describe('the file viewer (ADR-0051)', () => {
  it('puts an SVG in an <img> served by the deliverables route, never in a frame, an object or the page itself', async () => {
    const w = await show(entry({ path: 'docs/diagram.svg', kind: 'IMAGE', size: 120 }))
    const img = w.find('img.image')
    expect(img.exists()).toBe(true)
    const src = new URL(img.attributes('src')!, 'http://floor42.local')
    expect(src.pathname).toBe('/api/missions/M-1/deliverables/file')
    expect(src.searchParams.get('path')).toBe('docs/diagram.svg')
    expect(src.searchParams.get('source')).toBe('WORKTREE')
    expect(src.searchParams.get('download')).toBeNull()
    expect(w.findAll('iframe, object, embed, svg')).toHaveLength(0)
    w.unmount()
  })

  it('shows a Markdown file escaped: a script in it is text, never an element', async () => {
    text.value = '# Notes\n\n<script>window.hit = 1</script>\n<img src=x onerror="window.hit = 2">'
    const w = await show(entry({ path: 'notes.md' }))
    expect(w.text()).toContain('Notes')
    expect(w.findAll('script')).toHaveLength(0)
    expect(w.findAll('img')).toHaveLength(0)
    expect((window as unknown as { hit?: number }).hit).toBeUndefined()
    w.unmount()
  })

  it('puts a PDF in a frame of the same route, which the browser shows in its own viewer', async () => {
    const w = await show(entry({ path: 'report.pdf', kind: 'PDF', size: 100 }))
    const frame = w.find('iframe.pdf')
    const src = new URL(frame.attributes('src')!, 'http://floor42.local')
    expect(src.pathname).toBe('/api/missions/M-1/deliverables/file')
    expect(src.searchParams.get('path')).toBe('report.pdf')
    // No sandbox attribute: Chromium refuses a sandboxed PDF (measured in missionDeliverables.e2e.ts, ADR-0051).
    expect(frame.attributes('sandbox')).toBeUndefined()
    expect(w.findAll('object, embed')).toHaveLength(0)
    w.unmount()
  })

  it('says why a file is not shown and keeps Download', async () => {
    const big = await show(entry({ path: 'big.txt', size: 5000 }))
    expect(big.find('.refusal').text()).toContain('above the 1.0 KB the viewer shows')
    expect(big.find('a[download]').attributes('href')).toContain('download=true')
    big.unmount()
    const odd = await show(entry({ path: 'tool.exe', kind: 'OTHER' }))
    expect(odd.find('.refusal').text()).toContain('does not show this kind of file')
    expect(odd.find('[data-no-preview] a[download]').attributes('href')).toContain('download=true')
    odd.unmount()
  })

  it('fits an image by default, then shows it at actual size and zooms by steps', async () => {
    const w = await show(entry({ path: 'photo.png', kind: 'IMAGE', size: 120 }))
    expect(w.find('.image-frame.fit').exists()).toBe(true)
    const img = w.find('img.image')
    Object.defineProperty(img.element, 'naturalWidth', { value: 200 })
    Object.defineProperty(img.element, 'naturalHeight', { value: 100 })
    await img.trigger('load')
    await w.find('[aria-label="Zoom in"]').trigger('click')
    expect(w.find('.image-frame.fit').exists()).toBe(false)
    expect(w.find('[data-zoom]').text()).toBe('100 %')
    await w.find('[aria-label="Zoom in"]').trigger('click')
    expect(img.attributes('style')).toContain('width: 300px')
    await w.find('button[aria-pressed]').trigger('click')
    expect(w.find('.image-frame.fit').exists()).toBe(true)
    w.unmount()
  })

  it('offers Download when the browser cannot draw the image (refused, or bytes that are no image)', async () => {
    const w = await show(entry({ path: 'trap.png', kind: 'IMAGE', size: 40 }))
    await w.find('img.image').trigger('error')
    expect(w.find('img').exists()).toBe(false)
    expect(w.find('[data-no-preview]').text()).toContain('could not draw it as an image')
    expect(w.find('[data-no-preview] a[download]').attributes('href')).toContain('download=true')
    w.unmount()
  })

  it('asks the daemon first: a refused .pdf or image never reaches a frame or an <img>', async () => {
    viewable.value = false
    for (const file of [entry({ path: 'fake.pdf', kind: 'PDF', size: 100 }), entry({ path: 'trap.png', kind: 'IMAGE', size: 40 })]) {
      const w = await show(file)
      expect(w.findAll('iframe, img')).toHaveLength(0)
      expect(w.find('[data-no-preview] a[download]').attributes('href')).toContain('download=true')
      w.unmount()
    }
    viewable.value = true
  })

  it('shows the card, not the JSON refusal, when the daemon refuses the bytes of a .pdf', async () => {
    const framedAs = async (contentType: string) => {
      const w = await show(entry({ path: 'report.pdf', kind: 'PDF', size: 100 }))
      const frame = w.find('iframe.pdf')
      Object.defineProperty(frame.element, 'contentDocument', { value: { URL: 'http://floor42.local/api', contentType } })
      await frame.trigger('load')
      return w
    }
    const refused = await framedAs('application/json')
    expect(refused.find('iframe').exists()).toBe(false)
    expect(refused.find('[data-no-preview]').text()).toContain('did not send it as a PDF')
    refused.unmount()
    const shown = await framedAs('application/pdf')
    expect(shown.find('iframe.pdf').exists()).toBe(true)
    expect(shown.find('[data-no-preview]').exists()).toBe(false)
    shown.unmount()
  })

  it("reads the director's own file through the director's files route, never the deliverables one", async () => {
    const w = mount(FileViewerPanel, {
      props: { file: { mission: 'M-1', agent: '', path: 'in/photo.png', committed: false, own: true } },
      attachTo: document.body,
    })
    await flushPromises()
    const src = new URL(w.find('img.image').attributes('src')!, 'http://floor42.local')
    expect(src.pathname).toBe('/api/missions/M-1/files/file')
    expect(src.searchParams.get('path')).toBe('in/photo.png')
    expect(w.find('a[download]').attributes('href')).toBe('/api/missions/M-1/files/file?path=in%2Fphoto.png&download=true')
    expect(w.text()).toContain('your file, not committed yet')
    w.unmount()
  })

  it('keeps the file shown now when an earlier file answers late', async () => {
    slow.path = 'a.md'
    slow.gate = new Promise<void>((resolve) => (slow.release = resolve))
    text.value = 'NEW FILE TEXT'
    const a = entry({ path: 'a.md' })
    const b = entry({ path: 'b.md' })
    const w = await show(a)
    await w.setProps({ file: { mission: 'M-1', agent: 'dev-1', path: 'b.md', committed: false, file: b, view: caps } })
    await flushPromises()
    slow.release()
    await flushPromises()
    expect(w.text()).toContain('NEW FILE TEXT')
    expect(w.text()).not.toContain('OLD FILE TEXT')
    slow.path = ''
    w.unmount()
  })
})
