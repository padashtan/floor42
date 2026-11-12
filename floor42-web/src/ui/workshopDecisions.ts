import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import { api } from '../api/client'
import type { ChangeView, WorkshopView } from '../api/types'
import { workshopChangesApi } from '../api/workshopChanges'
import { t } from '../i18n'
import { confirmed } from '../state/confirm'
import { workshopCall } from '../state/office'

/** What the workshop's accept, reject and per-file discard do with the pending changes the director ticked. */
export interface Decisions {
  mission: () => string
  agent: Ref<string | null>
  /** The file the viewer shows. */
  path: Ref<string | null>
  changes: ComputedRef<ChangeView[]>
  message: Ref<string>
  reason: Ref<string>
  act: (run: () => Promise<WorkshopView | null>) => Promise<WorkshopView | null | undefined>
  /** The file viewer shows nothing once the work it showed is gone. */
  clearViewer: () => void
}

export function useWorkshopDecisions(d: Decisions) {
  /** The pending files the director left out of the next accept; all are ticked until one is unticked. */
  const unticked = ref<string[]>([])
  // Another workspace has other files: what was unticked in the last one must not leave a namesake out here.
  watch(d.agent, () => (unticked.value = []))
  const picked = computed(() => d.changes.value.map((c) => c.path).filter((p) => !unticked.value.includes(p)))

  async function accept() {
    if (!d.agent.value || !d.message.value.trim()) return
    // Without a selection the server commits every pending change: the same request as before the checkboxes.
    const paths = unticked.value.length ? picked.value : undefined
    await d.act(() => workshopCall(() => api.acceptPatch(d.mission(), d.agent.value!, d.message.value.trim(), paths)))
  }

  async function reject() {
    if (!d.agent.value || !d.reason.value.trim()) return
    const message = t('ws.rejectConfirm', { count: d.changes.value.length })
    if (!(await confirmed({ message, confirmLabel: t('ws.reject'), danger: true }))) return
    const v = await d.act(() => workshopCall(() => api.rejectPatch(d.mission(), d.agent.value!, d.reason.value.trim())))
    if (v) {
      d.reason.value = ''
      d.clearViewer()
    }
  }

  async function discard(path: string) {
    if (!d.agent.value) return
    const message = t('ws.discardConfirm', { path })
    if (!(await confirmed({ message, confirmLabel: t('ws.discard'), danger: true }))) return
    const v = await d.act(() => workshopCall(() => workshopChangesApi.discard(d.mission(), d.agent.value!, path)))
    if (v && d.path.value === path) d.clearViewer()
  }

  return { unticked, picked, accept, reject, discard }
}
