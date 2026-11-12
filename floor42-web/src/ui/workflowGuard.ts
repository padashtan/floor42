import { ref } from 'vue'
import { t } from '../i18n'
import { confirmed } from '../state/confirm'
import { setPanel } from '../state/openPanels'

/**
 * The Workflows panel's unsaved changes (ADR-0052). The panel sets `workflowDirty`; every way out of it asks first:
 * its own selections, its close button and Escape (the panel table's `close`).
 */
export const workflowDirty = ref(false)

/** Asks to drop the draft when there is one; true when the director may go on. */
export async function mayLeaveWorkflow(): Promise<boolean> {
  if (!workflowDirty.value) return true
  return confirmed({ message: t('workflows.leaveConfirm'), confirmLabel: t('workflows.discard'), danger: true })
}

/** Closes the panel, after the question when a draft would be lost. */
export async function closeWorkflows(): Promise<void> {
  if (!(await mayLeaveWorkflow())) return
  workflowDirty.value = false
  setPanel('workflows', false)
}
