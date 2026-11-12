import { type Ref, ref, watch } from 'vue'
import { api } from '../api/client'
import type { ArchiveView } from '../api/types'
import { office, onLiveEvent } from './office'

/** Events after which git is read again: commits come from agents' shell calls and the director's accepted patches. */
const COMMITTING = ['ActionExecuted', 'PatchAccepted', 'BranchesMerged', 'TicketSubmittedForReview']

/**
 * What git said of the mission's repository and its agents' branches (W6), read when the mission changes and shortly
 * after its work may have committed; `drawn` is given it each time it was read. Call once, from App.vue's setup.
 */
export function useArchive(drawn: (archive: ArchiveView | null) => void): Ref<ArchiveView | null> {
  const archive = ref<ArchiveView | null>(null)
  let timer: number | undefined
  async function load(): Promise<void> {
    const id = office.mission?.id
    if (!id) {
      archive.value = null
    } else {
      try {
        const a = await api.archive(id)
        if (office.mission?.id === id) archive.value = a
      } catch {
        archive.value = null
      }
    }
    drawn(archive.value)
  }
  watch(() => office.mission?.id, () => void load(), { immediate: true })
  onLiveEvent((e) => {
    if (!COMMITTING.includes(e.type)) return
    window.clearTimeout(timer)
    timer = window.setTimeout(() => void load(), 1500)
  })
  return archive
}
