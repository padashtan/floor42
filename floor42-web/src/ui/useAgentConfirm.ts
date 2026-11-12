import { t } from '../i18n'
import { noteReason } from '../i18n/events'
import { askReason, confirmed } from '../state/confirm'
import { dismissAgent, office, quarantineSession, suspendAgent } from '../state/office'

/** Dismissal, from the menu or the profile: the director confirms and gives a reason; the server may still refuse. */
export async function fire(id: string): Promise<boolean> {
  const name = office.agents.find((a) => a.id === id)?.name ?? id
  const reason = await askReason({
    message: t('prompt.dismiss', { name }),
    confirmLabel: t('confirm.dismiss'),
    danger: true,
    reason: noteReason('prompt.dismissDefault'),
  })
  return reason !== null && dismissAgent(id, name, reason)
}

export async function suspend(id: string) {
  const name = office.agents.find((a) => a.id === id)?.name ?? id
  const reason = await askReason({
    message: t('prompt.suspend', { name }),
    confirmLabel: t('confirm.suspend'),
    reason: noteReason('prompt.suspendDefault'),
  })
  if (reason !== null) void suspendAgent(id, reason)
}

/** The director's quarantine of a risky session: the dialog says what happens, nothing is sent before the confirm. */
export async function proposeQuarantine(mission: string, agent: string): Promise<boolean> {
  const name = office.agents.find((a) => a.id === agent)?.name ?? agent
  const ok = await confirmed({ message: t('prompt.quarantine', { agent: name, mission }), confirmLabel: t('confirm.quarantine'), danger: true })
  return ok && quarantineSession(mission, agent)
}
