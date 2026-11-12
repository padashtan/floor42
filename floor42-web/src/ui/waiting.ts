import type { AskAgainView, TicketView } from '../api/types'
import { t } from '../i18n'

/** The board card's word for a ticket an open question of the director's holds; null when none holds it. */
export function waitingLabel(ticket: Pick<TicketView, 'waitingFor'>): string | null {
  return ticket.waitingFor ? t('board.waitingForAnswer', { question: ticket.waitingFor }) : null
}

/** The choice that lifts one remembered refusal while answering: the exact call, never a guess from text. */
export function askAgainLabel(a: AskAgainView): string {
  return t('question.askAgain', { tool: a.tool, summary: a.summary, question: a.question })
}
