import * as THREE from 'three'
import type { Elevator } from './elevator'
import { Figure } from './figures'
import { lookOf, uniformOf } from './identity'
import type { AgentPresence } from './labels'
import { type Building, CONFERENCE, FLOOR, LANDING, PING_PONG_AT, type Slot } from './layout'
import { type Hands, toolRunning } from './life'
import { glyphFor, presentInOffice, stationFor } from './mapping'
import { type MeetingPlace, type MeetingPresence, meetingSign, placesOf, signLit, stationsAt } from './meetingPlaces'
import { crosses, NavGrid, type Rect } from './nav'
import { Seating } from './seating'
import type { Talk } from './talk'
import { PING_PONG } from './wing'

/** What the crew tells the scene: an agent's tag to bring up to date, an agent gone for good. */
export interface CrewEvents {
  placed(a: AgentPresence): void
  removed(id: string): void
  /** The tables whose meeting sign is lit now (ADR-0049). */
  signs?(lit: MeetingPlace[]): void
}

/**
 * The agents' figures in the office: where each one goes for its status, how they come up and down in the elevator,
 * the desks they keep, the chat table and the ping-pong rally. Agents come up when work is given to them (or when
 * hired with work), and take the elevator down when they have none ({@link presentInOffice}), at night, or for good
 * when dismissed (no longer listed).
 */
export class Crew {
  readonly figures = new Map<string, Figure>()
  /** How agents come and go: up from the street when hired or in the morning, down when dismissed or at night. */
  readonly elevator: Elevator
  /** Agents gathered at the meeting table for a mission's end (ADR-0039). */
  gathering = new Set<string>()
  night = false
  agents: AgentPresence[] = []
  /** The hands each agent's latest recorded tool call calls for (a read, or anything typed). */
  readonly handsOf = new Map<string, Hands>()
  readonly nav: NavGrid
  readonly seating: Seating
  /** Agents walking out for good (dismissed): removed once down the elevator. */
  private readonly leaving = new Set<string>()
  /** Agents a stuck mission would put to work (setStuck). */
  private stuckIds = new Set<string>()
  private populated = false
  private chatTimer = 0
  /** The director's open meetings (ADR-0049): where their seated attendees sit, which table's sign is lit. */
  private meetings: MeetingPresence[] = []
  private places = new Map<string, MeetingPlace>()
  /** Over the conference table, made the first time a meeting sits there. */
  private conferenceSign: THREE.Group | null = null
  private readonly building: Building
  private readonly talk: Talk
  private readonly events: CrewEvents

  constructor(building: Building, elevator: Elevator, talk: Talk, events: CrewEvents) {
    this.building = building
    this.elevator = elevator
    this.talk = talk
    this.events = events
    this.seating = new Seating(building.stations)
    this.nav = new NavGrid(FLOOR.width, FLOOR.depth, building.obstacles)
  }

  /** Creates figures for new agents and sends each one where its status calls for; the unlisted ones walk out. */
  setAgents(agents: AgentPresence[]): void {
    this.agents = agents
    const present = new Set(agents.map((a) => a.id))
    for (const [id, figure] of [...this.figures]) {
      if (present.has(id) || this.leaving.has(id)) continue
      // Already out (at night): gone for good without a walk.
      if (!figure.root.visible) {
        this.remove(id)
        continue
      }
      this.leaving.add(id)
      this.walkTo(figure, this.doorSlot())
    }
    agents.forEach((a) => { this.seating.setRemote(a.id, !!a.remote); this.place(a) })
    this.showSigns(agents)
    this.populated = true
  }

  /** The director's open meetings: their attendees, once seated, walk to the chosen table; they walk back after. */
  setMeetings(meetings: MeetingPresence[]): void {
    this.meetings = meetings
    this.places = placesOf(meetings)
    this.setAgents(this.agents)
  }

  /**
   * The meeting's sign over the table it sits at, while someone is seated there (ADR-0047, 0049). An attendee of a
   * meeting the scene was not told about sits at the meeting table, as before.
   */
  private showSigns(agents: AgentPresence[]): void {
    const seated = new Set(agents.filter((a) => a.status === 'IN_MEETING' && !a.remote).map((a) => a.id))
    const unplaced = [...seated].some((a) => !this.places.has(a))
    this.building.table.setMeeting(unplaced || signLit(this.meetings, seated, 'TABLE'))
    const conference = signLit(this.meetings, seated, 'CONFERENCE')
    if (conference && !this.conferenceSign) {
      this.conferenceSign = meetingSign()
      this.conferenceSign.position.set(CONFERENCE.x, 0, CONFERENCE.z)
      this.building.scene.add(this.conferenceSign)
    }
    if (this.conferenceSign) this.conferenceSign.visible = conference
    this.events.signs?.((['TABLE', 'CONFERENCE'] as const).filter((p) => this.signShown(p)))
  }

  /** Whether a table's meeting sign shows: for tests and the e2e journey. */
  signShown(place: MeetingPlace): boolean {
    return place === 'CONFERENCE' ? !!this.conferenceSign?.visible : this.building.table.meetingShown()
  }

  /** One agent: its figure made if new, sent where its status calls for, its emblem, hands and tag brought up to date. */
  private place(a: AgentPresence): void {
    let figure = this.figures.get(a.id)
    const here = this.here(a)
    const slot = here ? this.slotOf(a) : this.doorSlot()
    if (!here) this.seating.leave(a.id)
    if (!figure) {
      figure = new Figure(a.id, uniformOf(a.id), lookOf(a.id))
      this.figures.set(a.id, figure)
      this.building.scene.add(figure.root)
      if (this.populated && here) {
        this.arrive(figure, a.id)
      } else {
        figure.placeAt(slot, slot.yaw, slot.pose)
        figure.root.visible = here
      }
    } else if (this.elevator.busyWith(figure)) {
      // In the elevator: it walks where it should once it steps out.
    } else if (!figure.root.visible && here) {
      this.arrive(figure, a.id)
    } else {
      this.walkTo(figure, slot)
    }
    figure.setGlyph(glyphFor(a.status))
    figure.setStuck(this.stuckIds.has(a.id))
    figure.setResting(a.status === 'AVAILABLE')
    figure.setWork(toolRunning(a.status), this.handsOf.get(a.id) ?? 'type')
    this.events.placed(a)
  }

  /** The night began before the page opened: everyone is already out, nobody is seen leaving now. */
  allOut(): void {
    const door = this.doorSlot()
    for (const f of this.figures.values()) {
      f.placeAt(door, door.yaw, 'stand')
      f.root.visible = false
    }
  }

  setStuck(agents: string[]): void {
    this.stuckIds = new Set(agents)
    for (const [id, figure] of this.figures) figure.setStuck(this.stuckIds.has(id))
  }

  /** Off its station, to go where the next {@link setAgents} sends it (a ceremony gathers or dismisses it). */
  unseat(id: string): void {
    this.seating.leave(id)
  }

  /** In the office now: not at night, not drawn remote, and only with work the journal records, or gathered for a mission's end. */
  here(a: AgentPresence): boolean {
    return !this.night && !a.remote && (presentInOffice(a.status, a.suspended) || this.gathering.has(a.id))
  }

  /** Idle in the lounge, in the office and not suspended: free to chat, joke, play. */
  idle(a: AgentPresence): boolean {
    return stationFor(a.status, a.resident) === 'lounge' && !a.suspended && this.here(a)
  }

  /** In the office, standing still and out of the elevator: free to gesture. */
  free(f: Figure): boolean {
    return f.root.visible && !f.walking && !this.elevator.busyWith(f)
  }

  /** The agents in the office standing or sitting still with no tool running: the ones a pet may keep company. */
  idleFigures(): Figure[] {
    const out: Figure[] = []
    for (const a of this.agents) {
      const f = this.figures.get(a.id)
      if (f && this.here(a) && this.free(f) && !toolRunning(a.status)) out.push(f)
    }
    return out
  }

  private doorSlot(): Slot {
    return this.building.stations.door[0]
  }

  /** Up from the street in the elevator, then out onto the landing and to where the agent's status sends it. */
  private arrive(figure: Figure, id: string): void {
    this.elevator.bringUp(figure, () => {
      const a = this.agents.find((x) => x.id === id)
      if (!a || !this.here(a) || this.leaving.has(id)) {
        figure.placeAt(LANDING, 0, 'stand')
        return
      }
      const slot = this.slotOf(a)
      figure.goTo([LANDING, ...this.nav.path(LANDING, slot)], slot.yaw, slot.pose)
    })
  }

  private walkTo(figure: Figure, slot: Slot): void {
    const from = { x: figure.root.position.x, z: figure.root.position.z }
    figure.goTo(this.nav.path(from, slot), slot.yaw, slot.pose)
  }

  /** See OfficeScene.placeProp. */
  placeProp(prop: THREE.Object3D, footprint: Rect, reach = 3): boolean {
    const keys = Object.values(this.building.stations).flat()
    const spot = this.nav.findSpot(footprint, LANDING, keys, reach)
    if (!spot) return false
    prop.position.set((spot.x0 + spot.x1) / 2, 0, (spot.z0 + spot.z1) / 2)
    this.building.scene.add(prop)
    this.nav.addObstacle(spot)
    for (const figure of this.figures.values()) {
      const end = figure.route.at(-1)
      const here = { x: figure.root.position.x, z: figure.root.position.z }
      if (!end || !crosses(here, figure.route, spot, this.nav.clearance)) continue
      const { yaw, pose } = figure.arrivalPose
      figure.goTo(this.nav.path(here, end), yaw, pose)
    }
    return true
  }

  /** Who reached the landing on the way out takes the elevator down: gone for the night, or for good. */
  private passDoor(): void {
    for (const [id, figure] of this.figures) {
      if (!figure.root.visible || figure.walking || this.elevator.busyWith(figure)) continue
      const a = this.agents.find((x) => x.id === id)
      const out = this.leaving.has(id) || !a || !this.here(a)
      if (!out) continue
      const d = Math.hypot(figure.root.position.x - LANDING.x, figure.root.position.z - LANDING.z)
      if (d > 0.6) continue
      this.elevator.takeDown(figure, () => {
        if (this.leaving.has(id)) this.remove(id)
      })
    }
  }

  /** An agent who left the company: its figure and desk are released. */
  private remove(id: string): void {
    const figure = this.figures.get(id)
    if (figure) this.building.scene.remove(figure.root)
    this.figures.delete(id)
    this.leaving.delete(id)
    this.seating.release(id)
    this.handsOf.delete(id)
    this.events.removed(id)
  }

  /** The slot an agent's status calls for, unless it is in a conversation at the chat table or at a mission's end. */
  private slotOf(a: AgentPresence): Slot {
    if (this.gathering.has(a.id)) return this.seating.claim(a.id, 'table-seat')
    const place = a.status === 'IN_MEETING' ? this.places.get(a.id) : undefined
    if (place) return this.seating.claimAny(a.id, stationsAt(place))
    for (const [pair, c] of this.talk.chats) {
      const role = c.thinker === a.id ? 0 : c.listener === a.id ? 1 : -1
      if (role >= 0) return this.seating.sit(a.id, pair * 2 + role)
    }
    return this.seating.claim(a.id, stationFor(a.status, a.resident))
  }

  /** The figure on a lounge slot, if it has arrived there. */
  private atLounge(slot: number): Figure | null {
    const id = this.seating.on('lounge', slot)
    const f = id ? this.figures.get(id) : undefined
    return f && f.root.visible && !f.walking && f.pose === 'play' ? f : null
  }

  /**
   * The ball goes back and forth only while two idle agents stand at the table: the rally follows their status, it
   * never says anything about work.
   */
  private playPingPong(t: number): void {
    const ball = this.building.ball
    const rally = !this.night && this.atLounge(0) !== null && this.atLounge(1) !== null
    ball.visible = rally
    if (!rally) return
    const half = PING_PONG.length / 2 + 0.25
    const phase = (t * 1.25) % 2
    const u = phase < 1 ? phase : 2 - phase
    const x = PING_PONG_AT.x - half + u * 2 * half
    const hop = Math.abs(Math.sin(u * Math.PI * 2))
    ball.position.set(x, PING_PONG.height + 0.05 + hop * 0.35, PING_PONG_AT.z + Math.sin(t * 2.1) * 0.25)
  }

  /** Conversations change twice a second: those who start or stop one walk to their new place. */
  private converse(t: number): void {
    const moved = this.talk.converse(t, this.agents, this.building.stations.chat.length / 2)
    const byId = new Map(this.agents.map((a) => [a.id, a]))
    for (const id of moved) {
      const a = byId.get(id)
      const f = this.figures.get(id)
      if (!a || !f || !f.root.visible || this.leaving.has(id) || this.elevator.busyWith(f)) continue
      this.walkTo(f, this.slotOf(a))
    }
  }

  /** Walks, the elevator, ping-pong; conversations and jokes. `ceremony` plays between the walks and the door. */
  update(t: number, dt: number, ceremony: (t: number) => void): void {
    for (const f of this.figures.values()) f.update(t, dt)
    ceremony(t)
    this.passDoor()
    this.elevator.update(dt)
    this.playPingPong(t)
    this.chatTimer += dt
    if (this.chatTimer > 0.5) {
      this.converse(t)
      this.chatTimer = 0
    }
    this.talk.tellJokes(t, this.agents, (a) => {
      const f = this.figures.get(a.id)
      return !!f && this.idle(a) && !f.walking && f.root.visible
    })
  }
}
