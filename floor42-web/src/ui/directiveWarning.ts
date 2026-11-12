import type { EnvironmentBlocker } from '../api/types'
import { t } from '../i18n'
import { confirmed } from '../state/confirm'

/**
 * The warning before a directive reopens a ticket whose last report names a step its environment prevents (a browser
 * the sandbox lacks, for instance): repeating the step will not provide it. Null when the report names none.
 */
export function directiveWarning(ticket: string, b: EnvironmentBlocker | null | undefined): string | null {
  if (!b) return null
  return b.step
    ? t('env.directiveWarning', { ticket, missing: b.missing, step: b.step })
    : t('env.directiveWarningNoStep', { ticket, missing: b.missing })
}

/** True when the directive may go: nothing blocks the ticket, or the director read the warning and confirmed. */
export async function directiveMayGo(ticket: string, b: EnvironmentBlocker | null | undefined): Promise<boolean> {
  const message = directiveWarning(ticket, b)
  return !message || (await confirmed({ message, confirmLabel: t('env.sendAnyway') }))
}
