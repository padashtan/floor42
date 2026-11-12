import { shallowRef } from 'vue'

/**
 * The one in-app confirmation (ConfirmDialog.vue), in place of the browser's confirm() and prompt(): themed, in the
 * page's language, reachable by keyboard. `reason` turns it into a question with a text the director may edit (the
 * old prompt()); an empty answer then falls back to the default text. A second request while one is open waits.
 */
export interface ConfirmRequest {
  message: string
  /** Label of the confirming button; the generic "Confirm" when absent. */
  confirmLabel?: string
  /** Marks the action as destructive: the confirming button is red and the focus starts on Cancel. */
  danger?: boolean
  /** Asks for a reason: its label and the text it starts with. */
  reason?: Reason
  /** Asks for a free text (a prompt()): the label, the starting text, and whether an empty text can be confirmed. */
  input?: { label: string; value?: string; required?: boolean }
}

/**
 * A reason: the text the field starts with (in the screen's language), and what is recorded when the director leaves it
 * as it is or empties it (`recorded`, in English: the journal stays language-neutral; `default` when absent).
 */
export interface Reason {
  label?: string
  default: string
  recorded?: string
}

/** `true` for a plain confirmation, the typed text when a reason or an input was asked, `null` when cancelled. */
export type ConfirmAnswer = true | string | null

interface Open {
  request: ConfirmRequest
  settle: (answer: ConfirmAnswer) => void
}

export const pending = shallowRef<Open | null>(null)
const queue: Open[] = []

function next(): void {
  pending.value = queue.shift() ?? null
}

export function ask(request: ConfirmRequest): Promise<ConfirmAnswer> {
  return new Promise((resolve) => {
    const open: Open = {
      request,
      settle: (answer) => {
        resolve(answer)
        next()
      },
    }
    if (pending.value) queue.push(open)
    else pending.value = open
  })
}

/** A yes or no question. */
export async function confirmed(request: Omit<ConfirmRequest, 'reason' | 'input'>): Promise<boolean> {
  return (await ask(request)) !== null
}

/**
 * A reason, as prompt() gave it: the typed text, the recorded default when left as shown, `null` when cancelled. The
 * dialog never confirms an empty reason (ConfirmDialog.vue), so there is no empty answer to fall back from.
 */
export async function askReason(
  request: Omit<ConfirmRequest, 'reason' | 'input'> & { reason: Reason },
): Promise<string | null> {
  const answer = await ask(request)
  if (answer === null) return null
  const typed = typeof answer === 'string' ? answer.trim() : ''
  const { default: shown, recorded = shown } = request.reason
  return typed === shown.trim() ? recorded : typed
}

/** Whether a confirmation is open: it is modal, so Escape and the page's shortcuts are its own. */
export const confirming = (): boolean => pending.value !== null
