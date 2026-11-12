import { watch } from 'vue'
import { shouldOpenOnEnd } from '../ui/deliverables'
import { panelsWithDraft } from './drafts'
import { office } from './office'

/** A mission that ends while the director watches opens its deliverables, once per mission (this browser remembers). */
export function openDeliverablesOnEnd(open: () => void): void {
  watch(
    () => [office.mission?.id, office.mission?.status] as const,
    ([id, status], before) => {
      if (!id || !status || before?.[0] !== id) return
      let storage: Storage | null = null
      try {
        storage = window.localStorage
      } catch {
        storage = null
      }
      if (shouldOpenOnEnd(before?.[1], status, id, storage)) open()
    },
  )
}

/**
 * What a mission's end does with its deliverables: opens them, unless one of the `shown` panels holds something the
 * director typed (state/drafts.ts), which they must not replace: `offer` then tells the director they are ready.
 */
export function deliverablesOnEnd(shown: readonly string[], open: () => void, offer: () => void): void {
  if (panelsWithDraft(shown).length === 0) open()
  else offer()
}
