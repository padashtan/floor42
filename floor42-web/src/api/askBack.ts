// "Ask back" (ADR-0059): kept apart from client.ts, which is at its size ceiling; contract.ts checks the types against
// openapi.json all the same.
import { request } from './request'
import type { QuestionView } from './types'

export const askBackApi = {
  /** Withdraws the agent's question with the director's words; 409 SESSION_GONE when its session no longer waits. */
  send: (question: string, text: string) =>
    request<QuestionView>('POST', `/api/questions/${encodeURIComponent(question)}/ask-back`, { text }),
}
