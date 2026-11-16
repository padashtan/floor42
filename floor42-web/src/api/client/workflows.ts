import type {
  ImportView,
  WorkflowFileView,
  WorkflowsView,
} from '../types'
import { request } from '../request'

export const workflowsApi = {
  workflows: () => request<WorkflowsView>('GET', '/api/workflows'),
  saveWorkflow: (name: string, steps: readonly object[], replace: boolean, proposal: number | null = null) =>
    request<WorkflowsView>('POST', '/api/workflows', { name, steps, replace, proposal }),
  removeWorkflow: (name: string) => request<WorkflowsView>('POST', '/api/workflows/remove', { name }),
  exportWorkflow: (name: string) =>
    request<WorkflowFileView>('GET', `/api/workflows/export?name=${encodeURIComponent(name)}`),
  /** Checks a file's text in full; saves nothing. */
  importWorkflow: (text: string) => request<ImportView>('POST', '/api/workflows/import', { text }),
  dismissWorkflowProposal: (proposal: number) =>
    request<WorkflowsView>('POST', `/api/workflows/proposals/${proposal}/dismiss`),
}
