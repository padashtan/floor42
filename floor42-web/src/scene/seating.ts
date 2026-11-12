import type { Slot } from './layout'
import type { StationId } from './mapping'

/**
 * Who stands or sits where. Each agent keeps one desk for good, given the first time it needs one; at any other
 * station it keeps its slot while the station is unchanged, otherwise takes the first free one.
 */
export class Seating {
  private readonly stationOf = new Map<string, { station: StationId; slot: number }>()
  /** Desk index of each agent: given once, kept until the agent leaves the company. */
  private readonly deskOf = new Map<string, number>()
  private readonly stations: Record<StationId, Slot[]>
  /** The desks of the agents drawn remote today (remote.ts): empty chairs that get a sign. */
  readonly remote = new Map<string, Slot>()

  constructor(stations: Record<StationId, Slot[]>) {
    this.stations = stations
  }

  /** Off its station (out of the office, or sent elsewhere): its slot is free again. Its desk stays its own. */
  leave(id: string): void {
    this.stationOf.delete(id)
  }

  /** Gone from the company: its desk is free too. */
  release(id: string): void {
    this.stationOf.delete(id)
    this.remote.delete(id)
    this.deskOf.delete(id)
  }

  setRemote(id: string, on: boolean): void {
    if (on) this.remote.set(id, this.desk(id))
    else this.remote.delete(id)
  }

  /** Puts an agent on a given slot of the chat table. */
  sit(id: string, slot: number): Slot {
    this.stationOf.set(id, { station: 'chat', slot })
    return this.stations.chat[slot]
  }

  /** The agent on a slot of a station, if any. */
  on(station: StationId, slot: number): string | undefined {
    for (const [id, s] of this.stationOf) if (s.station === station && s.slot === slot) return id
    return undefined
  }

  /** Keeps an agent on its slot while its station is unchanged; otherwise takes the first free slot. */
  claim(agentId: string, station: StationId): Slot {
    if (station === 'desk') return this.desk(agentId)
    const slots = this.stations[station]
    const current = this.stationOf.get(agentId)
    if (current?.station === station) return slots[current.slot]
    const taken = new Set(
      [...this.stationOf.entries()].filter(([id, s]) => id !== agentId && s.station === station).map(([, s]) => s.slot),
    )
    const index = slots.findIndex((_, i) => !taken.has(i))
    // A full station (the sofa, the board): the agent waits at its own desk instead of standing on someone.
    if (index < 0) return this.claim(agentId, 'desk')
    this.stationOf.set(agentId, { station, slot: index })
    return slots[index]
  }

  /**
   * The first of these stations with a free slot (the agent's own slot there kept), else its desk: a meeting's chairs
   * first, then its standing places (ADR-0049).
   */
  claimAny(agentId: string, stations: StationId[]): Slot {
    for (const station of stations) {
      const current = this.stationOf.get(agentId)
      if (current?.station === station) return this.stations[station][current.slot]
      if (this.freeSlot(agentId, station) >= 0) return this.claim(agentId, station)
    }
    return this.claim(agentId, 'desk')
  }

  private freeSlot(agentId: string, station: StationId): number {
    const taken = new Set(
      [...this.stationOf.entries()].filter(([id, s]) => id !== agentId && s.station === station).map(([, s]) => s.slot),
    )
    return this.stations[station].findIndex((_, i) => !taken.has(i))
  }

  /** One desk each, for good: the first free one the first time the agent needs it. */
  private desk(agentId: string): Slot {
    const slots = this.stations.desk
    let desk = this.deskOf.get(agentId)
    if (desk === undefined) {
      const taken = new Set(this.deskOf.values())
      desk = slots.findIndex((_, i) => !taken.has(i))
      if (desk < 0) desk = Math.max(slots.length, ...taken) + 1
      this.deskOf.set(agentId, desk)
    }
    this.stationOf.set(agentId, { station: 'desk', slot: desk })
    // No desk left: a standing spot along the south edge of the open space, one each.
    if (desk >= slots.length) {
      const n = desk - slots.length
      return { x: 1.4 + (n % 10) * 1.1, z: 14.7, yaw: Math.PI, pose: 'stand' }
    }
    return slots[desk]
  }
}
