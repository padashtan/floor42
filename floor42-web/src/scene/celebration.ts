import * as THREE from 'three'
import { ceremonyPlan, dueSteps, type CeremonyStep } from './ceremony'
import type { Crew } from './crew'
import type { Effects } from './effects'

/**
 * A mission's end played in the office (ADR-0039): those who worked on it gather at the meeting table, those out come
 * up in the elevator; confetti and a cheer; then each goes where its status sends it. The plan is ceremony.ts's.
 */
export class Celebration {
  private ceremony: { plan: CeremonyStep[]; start: number; last: number } | null = null
  private readonly crew: Crew
  private readonly table: THREE.Object3D
  private readonly effects: Effects

  constructor(crew: Crew, table: THREE.Object3D, effects: Effects) {
    this.crew = crew
    this.table = table
    this.effects = effects
  }

  /** Reduced motion or the night: nothing (ceremonyPlan returns no step). */
  start(participants: string[], seats: number, now: number, calm: boolean): void {
    const known = new Set(this.crew.agents.map((a) => a.id))
    const plan = ceremonyPlan({ participants: participants.filter((p) => known.has(p)), seats, calm, night: this.crew.night })
    if (plan.length === 0) return
    this.ceremony = { plan, start: now, last: -1 }
  }

  /** Plays the steps due by `t`. */
  play(t: number, calm: boolean): void {
    const c = this.ceremony
    if (!c) return
    const elapsed = t - c.start
    for (const step of dueSteps(c.plan, c.last, elapsed)) {
      if (step.kind === 'cheer') {
        this.cheer(step.agents, t, calm)
        continue
      }
      this.crew.gathering = step.kind === 'gather' ? new Set(step.agents) : new Set()
      if (step.kind !== 'gather') this.ceremony = null
      for (const id of step.agents) this.crew.unseat(id)
      this.crew.setAgents(this.crew.agents)
    }
    if (this.ceremony) this.ceremony.last = elapsed
  }

  private cheer(agents: string[], t: number, calm: boolean): void {
    if (calm || this.crew.night) return
    for (const id of agents) {
      const f = this.crew.figures.get(id)
      if (f && this.crew.free(f)) f.play('celebrate', t)
    }
    const at = this.table.getWorldPosition(new THREE.Vector3())
    at.y += 1.6
    this.effects.burst('confetti', at, 90)
  }
}
