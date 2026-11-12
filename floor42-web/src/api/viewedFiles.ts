import { deliverableUrl, type DeliverableRef } from './client'
import { ApiError, REQUEST_HEADER, request } from './request'
import type { DirectorFileView } from './types'

/**
 * Where the viewer panel reads a file (ADR-0051): a deliverable of an agent, or one of the director's own pending
 * files in the mission's project. Either way the daemon decides what may be read and how it is sent.
 */
export type FileSource = { kind: 'deliverable'; ref: DeliverableRef } | { kind: 'own'; path: string }

const filesPath = (mission: string) => `/api/missions/${encodeURIComponent(mission)}/files`

/** The address of the file: shown (sandboxed by the daemon) or downloaded as an attachment. */
export function fileUrl(mission: string, source: FileSource, download: boolean): string {
  if (source.kind === 'deliverable') return deliverableUrl(mission, source.ref, download)
  const q = new URLSearchParams({ path: source.path })
  if (download) q.set('download', 'true')
  return `${filesPath(mission)}/file?${q.toString()}`
}

/** One of the director's pending files as the viewer needs it first: kind, size, and the view caps. */
export const ownFileEntry = (mission: string, path: string) =>
  request<DirectorFileView>('GET', `${filesPath(mission)}/entry?${new URLSearchParams({ path }).toString()}`)

/** What the viewer would get, said by the daemon in a 200 without the bytes (`check=true`). */
export interface ViewCheck {
  viewable: boolean
  type: string | null
  code: string | null
  message: string | null
}

/**
 * Asked before an image goes into an <img> or a PDF into a frame: a refusal then shows as the "no preview" card, never
 * as a failed load (or the daemon's JSON inside the frame).
 */
export const fileCheck = (mission: string, source: FileSource) =>
  request<ViewCheck>('GET', `${fileUrl(mission, source, false)}&check=true`)

/** A file's text, for the text viewer; the daemon refuses anything that is not text within its cap. */
export async function fileText(mission: string, source: FileSource): Promise<string> {
  const response = await fetch(fileUrl(mission, source, false), { credentials: 'same-origin', headers: { ...REQUEST_HEADER } })
  if (!response.ok) {
    const problem: { code?: string; message?: string } = await response.json().catch(() => ({}))
    throw new ApiError(response.status, problem.code ?? `HTTP_${response.status}`, problem.message ?? response.statusText)
  }
  return response.text()
}
