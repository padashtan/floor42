import { afterEach, describe, expect, it, vi } from 'vitest'
import { en } from '../i18n/en'
import { fr } from '../i18n/fr'
import type { ChecklistRow } from '../api/types'
import { checklistShown, firstShow, itemKey, purposeKey } from './checklist'

const row = (id: string, sandbox: boolean): ChecklistRow => ({
  id,
  sandbox,
  state: 'PRESENT',
  required: !sandbox,
  version: null,
  note: null,
  detail: null,
  command: null,
  measuredAt: null,
})

/** The ids MachineChecklist.java sends, in its order (host rows, then SandboxToolkit.ESSENTIALS, then docker). */
const HOST = ['claude', 'git', 'wsl', 'distro', 'isolation', 'pasta']
/** The rows NativeChecks adds for a tool the sandbox does not show: one id per tool of ToolchainMounts. */
const HIDDEN = ['java', 'node', 'mvn', 'npm', 'gradle', 'python3'].map((t) => `hidden.${t}`)
const SANDBOX = ['git', 'python3', 'node', 'java', 'make', 'chromium', 'pdftotext', 'pandoc', 'libreoffice', 'docker']
const NOTES = ['old', 'notFound', 'wsl1', 'notWindows', 'notMeasured', 'probeFailed', 'dockerReachable', 'userns', 'hiddenTool']

function memory(): Pick<Storage, 'getItem' | 'setItem'> {
  const m = new Map<string, string>()
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v) }
}

describe('machine checklist', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('is shown once per browser, at the first signed-in page', () => {
    vi.stubGlobal('window', { localStorage: memory() })
    expect(firstShow(false)).toBe(false)
    expect(firstShow(true)).toBe(true)
    expect(checklistShown()).toBe(true)
    expect(firstShow(true)).toBe(false)
  })

  it('a browser that refuses storage still shows it, and nothing breaks', () => {
    const denied = () => {
      throw new Error('denied')
    }
    vi.stubGlobal('window', { localStorage: { getItem: denied, setItem: denied } })
    expect(firstShow(true)).toBe(true)
    expect(checklistShown()).toBe(false)
    vi.stubGlobal('window', {
      get localStorage() {
        return denied()
      },
    })
    expect(firstShow(true)).toBe(true)
  })

  it('every row, state and note has its words in both catalogues', () => {
    const keys = [
      ...[...HOST, ...HIDDEN].map((id) => itemKey(row(id, false))!),
      ...[...HOST, ...HIDDEN].map((id) => purposeKey(row(id, false))),
      ...SANDBOX.map((id) => purposeKey(row(id, true))),
      ...['PRESENT', 'MISSING', 'OPTIONAL', 'UNKNOWN'].map((s) => `checklist.state.${s}`),
      ...NOTES.map((n) => `checklist.note.${n}`),
      'q.missingTools.subject',
      'q.missingTools.context',
      'q.missingToolsNoCommand.subject',
      'q.missingToolsNoCommand.context',
    ]
    for (const k of keys) {
      expect(en, k).toHaveProperty([k])
      expect(fr, k).toHaveProperty([k])
    }
    expect(purposeKey(row('git', true))).toBe('checklist.for.git.sandbox')
    expect(itemKey(row('chromium', true))).toBeNull()
  })
})
