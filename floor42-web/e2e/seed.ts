import type { Page, Route } from '@playwright/test'
import type { CommitteeView, ContributionView, MissionReport, ReportCheck } from '../src/api/types'
import { CATALOGS } from './fixtures'

/**
 * Filled panels for the screenshots. The daemon of the journeys has no recorded committee, no installed skills and
 * no measured sandbox, so the reads that fill those panels are answered with the daemon's own answer plus fixture
 * rows (the page code is the real one). Nothing here is sent to the daemon; it is for looking at the panels.
 */

const at = (minute: number) => `2026-11-03T09:${String(minute).padStart(2, '0')}:00Z`

const say = (
  member: string,
  role: string,
  round: number,
  kind: ContributionView['kind'],
  minute: number,
  proposal: string,
  text: string,
  position: string | null = null,
  criticalObjection: string | null = null,
): ContributionView => ({
  member,
  role,
  round,
  kind,
  text,
  proposal,
  position,
  criticalObjection,
  at: at(minute),
  byDirector: false,
  questions: [],
})

export const CONTRIBUTIONS: ContributionView[] = [
  say('Geoffrey', 'Software architect', 1, 'ANALYSIS', 1, 'Keep SQLite, add a read replica later', '## Analysis\n\nSQLite already holds the journal. A second store doubles the failure modes for no measured gain.', 'SQLITE'),
  say('Nora', 'Security specialist', 1, 'ANALYSIS', 2, 'Encrypt the file at rest', '## Analysis\n\nThe file holds approvals. It must not be readable by another user of the machine.', 'SQLITE', 'The journal file is world readable on some systems.'),
  say('Chris', 'Senior developer', 1, 'ANALYSIS', 3, 'Move to PostgreSQL now', '## Analysis\n\nPostgreSQL gives real concurrency; the migration is cheaper now than after release.', 'POSTGRES'),
  say('Geoffrey', 'Software architect', 2, 'REBUTTAL', 5, 'SQLite, with the file mode fixed', '## Rebuttal\n\nNo concurrency problem was measured. The file mode is a bug to fix, not a reason to change the store.', 'SQLITE'),
  say('Nora', 'Security specialist', 2, 'REBUTTAL', 6, 'SQLite once the file mode is fixed', '## Rebuttal\n\nI withdraw the encryption request if the mode is 0600.', 'SQLITE'),
  say('Chris', 'Senior developer', 2, 'REBUTTAL', 7, 'SQLite for now, revisit at 10 users', '## Rebuttal\n\nAccepted on the condition that the trigger to revisit is written down.', 'SQLITE'),
  say('Geoffrey', 'Software architect', 2, 'SYNTHESIS', 9, 'Adopt SQLite; fix the file mode; revisit at 10 concurrent users', '## Decision record\n\nAdopt SQLite. The journal file is created with mode 0600. Revisit when ten users write at once.'),
]

export function seededCommittee(directorSeat: string | null): CommitteeView {
  return {
    topic: 'Which store holds the journal: keep SQLite, or move to PostgreSQL now?',
    ticket: 'T-1',
    stage: 'DEBATE',
    round: 2,
    members: [
      { agent: 'Geoffrey', role: 'Software architect', chair: true, pending: false },
      { agent: 'Nora', role: 'Security specialist', chair: false, pending: false },
      { agent: 'Chris', role: 'Senior developer', chair: false, pending: true },
    ],
    contributions: CONTRIBUTIONS,
    constraints: ['No new service to install', 'Must run offline'],
    standingCriticalObjections: [],
    implement: true,
    panel: 'architecture',
    moderated: true,
    maxDebateRounds: 3,
    directorSeat,
    agreement: 'SQLITE',
    debateRounds: 2,
    analyst: null,
    clarificationNote: null,
    clarifications: 0,
    number: 2,
  }
}

// The patched bodies are the daemon's own JSON, edited key by key: typing each one would only copy the API types.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function patched(route: Route, patch: (body: any) => void): Promise<void> {
  const response = await route.fetch()
  const body = await response.json()
  patch(body)
  await route.fulfill({ response, json: body })
}

/** A recorded committee on the mission: two rounds, a decision record, an earlier committee. */
export async function seedMeeting(page: Page, mission: string): Promise<void> {
  await page.route(new RegExp(`/api/missions/${mission}$`), (route) =>
    patched(route, (m) => {
      m.status = 'IN_PROGRESS'
      m.committee = seededCommittee(m.committee?.directorSeat ?? 'Chris')
      m.committees = [
        { number: 1, topic: 'Which web framework?', ticket: 'T-0', stage: 'ADOPTED', recommendation: 'Quarkus, already in the module', implement: true },
        { number: 2, topic: m.committee.topic, ticket: 'T-1', stage: 'DEBATE', recommendation: null, implement: true },
      ]
    }),
  )
}

const CHECKS: ReportCheck[] = [
  { workspace: 'floor42-server', command: 'mvn -q test', exitCode: 0, timedOut: false, durationMs: 41000, trigger: 'ticket', at: at(10), passed: true },
  { workspace: 'floor42-web', command: 'npm test', exitCode: 1, timedOut: false, durationMs: 18000, trigger: 'ticket', at: at(20), passed: false },
  { workspace: 'floor42-web', command: 'npm test', exitCode: 0, timedOut: false, durationMs: 17000, trigger: 'ticket', at: at(30), passed: true },
  { workspace: 'floor42-web', command: 'npx vue-tsc -b --noEmit', exitCode: 0, timedOut: false, durationMs: 9000, trigger: 'director', at: at(40), passed: true },
]

/** The mission closed, with a report holding checks and reviews. */
export async function seedReport(page: Page, mission: string): Promise<void> {
  await page.route(new RegExp(`/api/missions/${mission}$`), (route) =>
    patched(route, (m) => {
      m.status = 'COMPLETED'
      m.outcome = 'Delivered to the repository'
    }),
  )
  await page.route(new RegExp(`/api/missions/${mission}/report$`), (route) =>
    patched(route, (r: MissionReport) => {
      r.checks = CHECKS
      r.reviews = { reviews: 5, validated: 3, withReservations: 1, rejected: 1, unverifiable: 0 }
      for (const [i, x] of r.tickets.entries()) {
        x.reviews = [
          { reviewer: 'Dehren', verdict: 'REJECTED', firstLine: 'The test for the empty list is missing.' },
          { reviewer: 'Dehren', verdict: i % 2 ? 'VALIDATED_WITH_RESERVATIONS' : 'VALIDATED', firstLine: 'The empty list is now covered.' },
        ]
      }
    }),
  )
}

/** Installed skills and agents, and suggestions, as if read from a user folder and a repository. */
export async function seedSkills(page: Page): Promise<void> {
  await page.route(/\/api\/catalogue/, (route) =>
    patched(route, (v) => {
      const item = (kind: string, name: string, description: string, state: string, level = 'USER') => ({
        kind, level, plugin: null, name, description, tools: ['Read', 'Grep'], model: null, version: null,
        path: `~/.claude/${kind === 'SKILL' ? 'skills' : 'agents'}/${name}`, problem: null, state, reason: state === 'AVAILABLE' ? 'inSession' : 'notInSession',
      })
      v.installed = [
        item('SKILL', 'sql-postgres', 'Diagnose a slow PostgreSQL query and fix a badly indexed schema.', 'AVAILABLE'),
        item('SKILL', 'code-review', 'Review the current diff for correctness bugs.', 'AVAILABLE'),
        item('SKILL', 'docker-craft', 'Build and debug container images.', 'NOT_AVAILABLE'),
        item('AGENT', 'code-reviewer', 'Review a diff for real defects, read only.', 'AVAILABLE', 'PROJECT'),
        item('AGENT', 'doc-writer', 'Write and update documentation.', 'UNKNOWN', 'PROJECT'),
      ]
    }),
  )
}

/** A sandbox with a few tools present and some missing, and the machine checklist to match. */
export async function seedSandbox(page: Page): Promise<void> {
  await page.route(/\/api\/sandbox\/readiness(\?.*)?$/, (route) =>
    patched(route, (v) => {
      const tool = (name: string, state: string, version: string | null, because: string[]) => ({
        tool: name, wanted: null, because, state, path: version ? `/usr/bin/${name}` : null, version, note: null,
      })
      v.measured = true
      v.measuredAt = '2026-11-03T08:30:00Z'
      v.enabled = true
      v.tools = [
        tool('git', 'PRESENT', '2.45.1', ['git repository']),
        tool('java', 'PRESENT', '25.0.5', ['pom.xml']),
        tool('node', 'OLDER', '18.19.0', ['package.json']),
        tool('maven', 'MISSING', null, ['pom.xml']),
        tool('chromium', 'MISSING', null, ['playwright']),
        tool('curl', 'PRESENT', '8.5.0', []),
      ]
      v.commands = ['sudo apt-get install -y maven', 'sudo apt-get install -y chromium']
    }),
  )
  await page.route(/\/api\/sandbox\/checklist$/, (route) =>
    patched(route, (c) => {
      const row = (id: string, sandbox: boolean, state: string, version: string | null, command: string | null) => ({
        id, sandbox, state, required: true, version, note: null, detail: null, command,
      })
      c.ready = false
      c.rows = [
        row('claude', false, 'PRESENT', '2.1.316', null),
        row('git', false, 'PRESENT', '2.45.1', null),
        row('wsl', false, 'PRESENT', '2.3', null),
        row('java', true, 'PRESENT', '25.0.5', null),
        row('maven', true, 'MISSING', null, 'sudo apt-get install -y maven'),
        row('chromium', true, 'MISSING', null, 'sudo apt-get install -y chromium'),
      ]
    }),
  )
}

/** Closes the "While you were away" summary, so nothing sits behind the panel being shot. */
export async function clearAway(page: Page): Promise<void> {
  await page.locator('.away-panel').first().waitFor({ state: 'visible', timeout: 2500 }).catch(() => undefined)
  const close = page.locator('.away-panel button.action', { hasText: CATALOGS.en['away.close'] })
  if (await close.count()) await close.first().click()
  await page.locator('.away-panel').waitFor({ state: 'detached', timeout: 3000 }).catch(() => undefined)
}
