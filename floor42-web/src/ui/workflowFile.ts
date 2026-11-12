import { api } from '../api/client'
import type { ImportProblemView } from '../api/types'
import { t, tr } from '../i18n'

/**
 * The workflow file (ADR-0052): what the screen sends to the daemon to check, what it saves to disk, and the sentence
 * for each problem code. The daemon decides what a file is worth; nothing here parses or trusts its content.
 */

/** The daemon's cap on a file; the screen never reads more than one byte past it. */
export const MAX_FILE_BYTES = 64 * 1024

/** `<name>.floor42-workflow.json`, with anything a file name should not hold replaced. */
export function fileName(name: string): string {
  return `${name.trim().replace(/[^\p{L}\p{Nd} ._'-]/gu, '_') || 'workflow'}.floor42-workflow.json`
}

/** The text of a file, cut one byte past the cap: a larger file is refused by the daemon by code, never read in full. */
export async function readFileText(file: File): Promise<string> {
  return file.slice(0, MAX_FILE_BYTES + 1).text()
}

/** The sentence for a problem of an imported file, in the screen's language; an unknown code gets a general one. */
export function importProblemText(p: ImportProblemView): string {
  return tr(`workflows.imp.${p.code}`, t('workflows.imp.unknown'), { subject: p.subject ?? t('workflows.imp.unnamed') })
}

/** Saves the company workflow of this name as a file, as the daemon wrote it. */
export async function downloadWorkflow(name: string): Promise<void> {
  const file = await api.exportWorkflow(name)
  const url = URL.createObjectURL(new Blob([JSON.stringify(file, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName(name)
  document.body.append(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
