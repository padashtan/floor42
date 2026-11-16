// The 9 o'clock standup (ADR-0078): mirrors of dev.floor42.server.app.office.standup.Standup.

/**
 * One line. `code`: SUBMITTED, COMPLETED, SESSIONS, SESSIONS_FAILED, IN_PROGRESS, IN_REVIEW, QUEUED, SUSPENDED, QUESTION
 * or DEPENDENCY. `count` is a number of sessions or of unmet dependencies. `text` is a ticket title or a question
 * subject: agent or director text, shown as plain text.
 */
export interface StandupLine {
  code: string
  mission: string | null
  ticket: string | null
  text: string | null
  count: number | null
}

export interface AgentStandup {
  agent: string
  name: string
  yesterday: StandupLine[]
  today: StandupLine[]
  blocked: StandupLine[]
}

/** `previousDay` is the last local day before `date` that holds a real event; null when none does. */
export interface Standup {
  date: string
  previousDay: string | null
  zone: string
  agents: AgentStandup[]
}
