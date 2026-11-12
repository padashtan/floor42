import { ref, type Ref } from 'vue'
import { api, ApiError } from '../api/client'
import { t } from '../i18n'
import { ask } from '../state/confirm'
import { workshopCall } from '../state/office'

export type Mode = 'diff' | 'file' | 'edit'

/**
 * What the workshop's viewer shows (a diff, a file, the editor) and how the director moves between files without losing
 * a draft: the newest open wins, and a draft that was typed and not saved is never dropped without a yes.
 */
export function useWorkshopEditor(mission: () => string | undefined, agent: Ref<string | null>) {
  const path = ref<string | null>(null)
  const mode = ref<Mode>('diff')
  const text = ref<string | null>(null)
  const binary = ref(false)
  const draft = ref('')
  const note = ref('')
  /** The newest call to show something: an earlier answer arriving late must not replace what is on screen now. */
  let latest = 0

  /** A draft the director typed and did not save: leaving it behind asks first (false on a no). */
  async function mayLeaveDraft(): Promise<boolean> {
    if (mode.value !== 'edit' || draft.value === (text.value ?? '')) return true
    return (await ask({ message: t('ws.discardDraft'), confirmLabel: t('ws.discardDraftConfirm'), danger: true })) === true
  }

  /** Shows a file or a diff, no question asked. */
  async function show(p: string, as: Mode) {
    const m = mission()
    const a = agent.value
    if (!m || !a) return
    const mine = ++latest
    path.value = p
    mode.value = as
    text.value = null
    binary.value = false
    if (as === 'diff') {
      const d = await workshopCall(() => api.workshopDiff(m, a, p), false)
      if (mine === latest) text.value = d?.diff ?? null
      return
    }
    const f = await workshopCall(() => api.workshopFile(m, a, p), false)
    if (mine !== latest) return
    binary.value = f?.binary ?? false
    text.value = f?.content ?? null
    draft.value = text.value ?? ''
    note.value = ''
  }

  /** The director opens a file or a diff: a dirty draft is not dropped without a yes. */
  async function open(p: string, as: Mode) {
    if (await mayLeaveDraft()) await show(p, as)
  }

  /** Editing starts from the file as it is on disk now; already editing this one, the draft stays as it is. */
  async function startEdit(p: string) {
    if (mode.value === 'edit' && path.value === p) return
    if (!(await mayLeaveDraft())) return
    await show(p, 'file')
    if (path.value === p && !binary.value && text.value !== null) mode.value = 'edit'
  }

  /** A new file's path: one the daemon already has is opened for editing; only its "no such file" makes it new. */
  async function newFile() {
    if (!(await mayLeaveDraft())) return
    const answer = await ask({ message: t('ws.newFilePrompt'), input: { label: t('ws.newFileLabel'), required: true } })
    const p = typeof answer === 'string' ? answer.trim() : ''
    const m = mission()
    if (!p || !m || !agent.value) return
    try {
      await api.workshopFile(m, agent.value, p)
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) {
        await workshopCall(() => Promise.reject(e), false)
        return
      }
      latest++
      path.value = p
      mode.value = 'edit'
      text.value = ''
      draft.value = ''
      binary.value = false
      return
    }
    await startEdit(p)
  }

  /** Nothing is shown: the viewer is cleared. */
  function clear() {
    latest++
    path.value = null
    text.value = null
    mode.value = 'diff'
  }

  return { path, mode, text, binary, draft, note, mayLeaveDraft, show, open, startEdit, newFile, clear }
}
