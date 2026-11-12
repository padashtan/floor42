import type { ArchiveDrawing, ClientState, IdentityHealth } from './props'
import * as THREE from 'three'
import type { EventView } from '../api/types'
import { CameraRig, type View } from './camera'
import { City } from './city'
import { Crew } from './crew'
import { disposeGlyphs, Figure } from './figures'
import type { SceneTarget } from './kit'
import { Elevator } from './elevator'
import { Mascots, type PetWords } from './mascots'
import { PetBubbles } from './petBubbles'
import { PetVoice } from './petSound'
import { Effects } from './effects'
import { SceneInput } from './input'
import { type AgentPresence, NameTags, type TagLines } from './labels'
import { officeMarks, screenMarks } from './marks'
import { SPEAK_SECONDS, speakerOf } from './presence'
import type { MeetingPresence } from './meetingPlaces'
import { OfficeLights } from './lighting'
import { Sky } from './sky'
import { Celebration } from './celebration'
import { buildOffice, ELEVATOR, FLOOR, type Building } from './layout'
import { gestureFor, handsFor, milestoneOf, toolRunning } from './life'
import { activityOf, type BoardColumns, glyphFor, machineReaction } from './mapping'
import type { Rect } from './nav'
import { applyTheme, type ThemeId } from './palette'
import { findRoot, type Hit, Picker, Selection } from './picking'
import { PixelPipeline } from './pixelPipeline'
import { RenderLoop } from './renderLoop'
import { disposeDrawn } from './resources'
import { Talk } from './talk'

export type { AgentPresence } from './labels'
export type { MeetingPresence } from './meetingPlaces'

export interface SceneCallbacks {
  onSelect(target: SceneTarget | null): void
  onContextMenu(target: SceneTarget, clientX: number, clientY: number): void
  onHover(target: SceneTarget | null, clientX: number, clientY: number): void
  /** A double click: on an agent, opens what it waits for. */
  onOpen?(target: SceneTarget): void
}

/**
 * The isometric office. Framework-free: the HUD tells it which agents exist and what they are doing, and forwards
 * live events; the scene reports what the director points at. It never invents activity. Its parts are modules of
 * their own (camera, input, picking, labels, renderLoop, crew, talk).
 */
export class OfficeScene {
  /** Built in the constructor, once the decade's palette is in force: its steel and chrome read it when built. */
  private readonly elevator: Elevator
  private readonly pipeline: PixelPipeline
  private readonly building: Building
  private readonly rig: CameraRig
  /** The city far below the office's floor: scenery only. */
  private readonly city: City
  /** Aircraft passing in the sky behind the office: scenery only. */
  private readonly sky: Sky
  private readonly crew: Crew
  /** The robot, the cat and the dog: built after the decade's palette, still under reduced motion. */
  private readonly mascots: Mascots
  private readonly petBubbles: PetBubbles
  private readonly petVoice = new PetVoice()
  private readonly loop = new RenderLoop((t, dt) => this.frame(t, dt))
  private readonly selection: Selection
  private readonly picker: Picker
  private readonly input: SceneInput
  private readonly tags: NameTags
  private readonly talk: Talk
  private readonly lights: OfficeLights
  private boardTimer = 0
  private moodTimer = 10
  private markTimer = 0
  private readonly mark = new THREE.Vector3()
  private readonly cleanup: (() => void)[] = []
  private readonly canvas: HTMLCanvasElement
  private readonly callbacks: SceneCallbacks
  /** False until the HUD has said once whether it is night: the first answer is a state, not an event. */
  private nightKnown = false
  /** The last live event about each agent, shown under its name for a few seconds. */
  private readonly activity = new Map<string, { text: string; tone: string; until: number }>()
  /** Confetti and puffs: started only by an event that calls for them. */
  private readonly effects = new Effects()
  /** A mission's end played at the meeting table (ADR-0039). */
  private readonly celebration: Celebration
  /** Who each ticket is assigned to, from the board: who celebrates a completed ticket. */
  private assignees: Record<string, string> = {}
  /** The director's "reduce motion" setting: no ambient motion, no particles, no glides. */
  private calm = false

  constructor(canvas: HTMLCanvasElement, labelLayer: HTMLElement | undefined, callbacks: SceneCallbacks, theme: ThemeId = '1990s', petSeed = 0) {
    this.canvas = canvas
    this.callbacks = callbacks
    this.pipeline = new PixelPipeline(canvas, applyTheme(theme).pixel)
    this.elevator = new Elevator(ELEVATOR.x, ELEVATOR.z)
    this.building = buildOffice()
    this.talk = new Talk({ night: () => this.crew.night, idle: (a) => this.crew.idle(a) })
    // The lit meeting signs, on the canvas for the browser tests (data only, ADR-0049).
    const signs = (lit: string[]) => canvas.dataset && (canvas.dataset.meetingSigns = lit.join(' '))
    this.crew = new Crew(this.building, this.elevator, this.talk, { placed: (a) => this.tag(a), removed: (id) => this.forget(id), signs })
    const scene = this.building.scene
    this.city = new City(FLOOR.width / 2, FLOOR.depth / 2, FLOOR.width, FLOOR.depth)
    this.city.root.userData.center = { x: FLOOR.width / 2, z: FLOOR.depth / 2 }
    this.sky = new Sky(FLOOR.width / 2, FLOOR.depth / 2, theme)
    scene.add(this.city.root, this.sky.root, this.elevator.root)
    scene.fog = City.fog((scene.background as THREE.Color).getHex())
    this.rig = new CameraRig(this.building.rooms[0].center)
    this.selection = new Selection(scene)
    scene.add(this.effects.mesh)
    this.mascots = new Mascots(scene, this.crew, petSeed)
    this.mascots.onVoice = (tag) => {
      if (!this.calm) this.petVoice.play(tag)
    }
    this.petBubbles = new PetBubbles(labelLayer, canvas)
    this.celebration = new Celebration(this.crew, this.building.table.root, this.effects)
    this.lights = new OfficeLights(this.building.lights)
    this.picker = new Picker(canvas, this.rig.camera, scene)
    this.tags = new NameTags(labelLayer, canvas, {
      select: (target) => callbacks.onSelect(target),
      open: (target) => callbacks.onOpen?.(target),
      menu: (target, x, y) => this.menu(target, x, y),
    }, this.crew.seating.remote)
    this.followMotionSetting()
    this.input = this.bindInput()
    this.resize()
    this.loop.start()
  }

  /** The director's "reduce motion" system setting, followed live. */
  private followMotionSetting(): void {
    const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    if (!motion) return
    const follow = () => {
      this.calm = motion.matches
      Figure.calm = motion.matches
    }
    follow()
    motion.addEventListener('change', follow)
    this.cleanup.push(() => motion.removeEventListener('change', follow))
  }

  /** Where the camera looks and how far it is zoomed: carried over when a new decade rebuilds the scene. */
  get view(): View {
    return this.rig.view
  }

  set view(v: View) {
    this.rig.view = v
  }

  get rooms(): { id: string; name: string }[] {
    return this.building.rooms.map((r) => ({ id: r.id, name: r.name }))
  }

  /** Measured frames per second and internal render size, for the HUD. */
  get performance(): { fps: number; resolution: string } {
    return { fps: this.loop.fps, resolution: `${this.rig.lowRes.x}×${this.rig.lowRes.y}` }
  }

  // ---- state from the HUD ----------------------------------------------------------------------------------------

  /** Figures for new agents, each sent where its status calls for: up the elevator for work, down without (or dismissed). */
  setAgents(agents: AgentPresence[]): void {
    this.crew.setAgents(agents)
  }

  /** The director's open meetings (ADR-0049): their seated attendees go to the chosen table, under its sign. */
  setMeetings(meetings: MeetingPresence[]): void {
    this.crew.setMeetings(meetings)
  }

  /** Night: the plan's quota is spent (measured by Claude Code). Lights down, screens off, everyone out till it ends. */
  setNight(on: boolean): void {
    const first = !this.nightKnown
    this.nightKnown = true
    if (on === this.crew.night) return
    this.crew.night = on
    this.lights.setNight(on)
    this.lights.mood(on)
    for (const desk of this.building.desks) {
      desk.traverse((o) => {
        if (o.userData.screen) o.visible = !on
      })
    }
    if (on && first) this.crew.allOut()
    this.crew.setAgents(this.crew.agents)
  }

  /** What the pets say, in the screen's language ("Miaou", "Ouaf", "z"). */
  setPetWords(words: PetWords): void {
    this.mascots.setWords(words)
  }

  /** Whether the pets make their synthesised sounds: off until the director turns it on, never under reduced motion. */
  setPetSound(on: boolean): void {
    this.petVoice.enabled = on
  }

  /** Call from a user gesture: lets the pets' sound start (browsers keep audio shut until then). */
  unlockPetSound(): void {
    this.petVoice.unlock()
  }

  /** The language idle agents joke in: the screen's. */
  setLanguage(lang: 'en' | 'fr'): void {
    this.talk.language = lang
  }

  /**
   * A mission was completed (MissionCompleted, ADR-0039): those who worked on it gather at the meeting table, those
   * out come up in the elevator; confetti and a cheer; then each goes where its status sends it. Reduced motion or the
   * night: nothing (ceremonyPlan returns no step).
   */
  celebrate(participants: string[]): void {
    this.celebration.start(participants, this.building.stations['table-seat'].length, this.loop.now, this.calm)
  }

  /**
   * Stands a prop on the floor after the office was built (`footprint` in world units, where the prop's origin is its
   * centre). If it would cut a desk, a station or the elevator's landing off, it moves to the nearest spot that does
   * not; if there is none, it is not placed. Agents walk around it from now on, and those already walking through it
   * take another way. Returns whether it was placed.
   */
  placeProp(prop: THREE.Object3D, footprint: Rect): boolean {
    return this.crew.placeProp(prop, footprint)
  }

  /** An agent who left the company: its tag, lines and selection go with it. */
  private forget(id: string): void {
    this.activity.delete(id)
    this.talk.forget(id)
    this.tags.remove(id)
    if (this.selection.selected?.target.kind === 'agent' && this.selection.selected.target.id === id) this.select(null)
  }

  /** The name tag that follows an agent; once the agent no longer waits, its pending request line is over. */
  private tag(a: AgentPresence): void {
    const act = this.activity.get(a.id)
    if (act?.tone === 'wait' && glyphFor(a.status) !== 'wait') this.activity.delete(a.id)
    this.tags.show(a)
  }

  /** This frame's lines under an agent's name: its live activity, else its joke; its own words while it thinks. */
  private linesOf(id: string, now: number): TagLines {
    const act = this.activity.get(id)
    const live = act && act.until > now ? act : null
    const told = this.talk.jokes.get(id)
    const thinking = this.talk.isThinker(id) && !this.crew.figures.get(id)?.walking
    return {
      doing: live,
      joke: told && told.until > now && !live ? told.text : '',
      said: thinking ? (this.talk.remarks.get(id) ?? '…') : '',
      speaking: !!this.crew.figures.get(id)?.speaks(now),
    }
  }

  setBoard(columns: BoardColumns): void {
    this.building.board.setColumns(columns)
    this.assignees = columns.assignees
  }

  /** The agents a stuck mission would put to work (the server's stall rule): a "?" over each, if in the office. */
  setStuck(agents: string[]): void {
    this.crew.setStuck(agents)
  }

  /** The archive wall (W6): what git said of the mission's repository and its agents' branches. */
  setArchive(drawing: ArchiveDrawing): void { this.building.archiveWall.setArchive(drawing) }

  /** The library's bookcase: one spine per project, in these colours (scene/cover.ts). */
  setLibrary(colors: number[]): void { this.building.library.setBooks(colors) }

  /** The identity server (Keycloak): the state of IAM mode and of each agent's client, as the daemon measured them. */
  setIdentity(health: IdentityHealth, clients: ClientState[]): void { this.building.identity.setState(health, clients) }

  /** Live event: machines react only to recorded executions; a denial shows an alert above the agent. */
  /** @param line the event's line in the screen's language, and how to say "waits for you: …" (default: its code) */
  react(ev: EventView, line?: { text: string; waits: (subject: string) => string }): void {
    const now = this.loop.now
    if (ev.agent) this.talk.lastSign.set(ev.agent, now)
    if (ev.type === 'AgentRemarked' && ev.agent && ev.summary) this.talk.remarks.set(ev.agent, ev.summary.replace(/^[^:]*:\s*/, ''))
    const reaction = machineReaction(ev)
    if (reaction) this.building.machines.get(reaction.machine)?.pulse(reaction.state, ev.summary ?? ev.type, now)
    const figure = ev.agent ? this.crew.figures.get(ev.agent) : undefined
    // A voice recorded at a committee or a meeting marks its speaker for a few seconds.
    if (speakerOf(ev) === ev.agent) figure?.speak(now + SPEAK_SECONDS)
    // Refused or failed: the emblem turns to the warning triangle for a moment.
    if ((ev.type === 'ActionDenied' || ev.type === 'ActionFailed') && figure) figure.alert(now)
    if (ev.agent && ev.category && figure) {
      // The hands follow the tool the journal records: a sheet for a read, the keyboard for the rest.
      this.crew.handsOf.set(ev.agent, handsFor(ev.category))
      const status = this.crew.agents.find((a) => a.id === ev.agent)?.status
      if (status) figure.setWork(toolRunning(status), handsFor(ev.category))
    }
    this.gesture(ev, now)
    const activity = activityOf(ev, line?.text ?? ev.code, line?.waits)
    const shown = ev.agent ? this.activity.get(ev.agent) : undefined
    // The question that follows a request says less than the request itself ("Bash: git status"): keep the request.
    const keep = ev.type === 'QuestionAsked' && shown?.tone === 'wait' && shown.until > now
    // A request stays up while it waits for a decision; anything else fades after a few seconds.
    if (activity && ev.agent && !keep) this.activity.set(ev.agent, { ...activity, until: now + (activity.tone === 'wait' ? 60 : 8) })
  }

  /**
   * The gesture and the burst an event calls for (see life.ts): a ticket done makes its assignee celebrate under
   * confetti, a refusal makes the agent hold its head in a small grey puff, a milestone throws confetti at the board.
   * Reduced motion keeps the emblems and drops all of this.
   */
  private gesture(ev: EventView, now: number): void {
    if (this.calm || this.crew.night) return
    const g = gestureFor(ev, (ticket) => this.assignees[ticket])
    const figure = g ? this.crew.figures.get(g.agent) : undefined
    if (g && figure?.root.visible && !this.elevator.busyWith(figure)) {
      figure.play(g.gesture, now)
      const at = figure.root.position.clone()
      at.y += g.gesture === 'celebrate' ? 1.9 : 1.75
      this.effects.burst(g.gesture === 'celebrate' ? 'confetti' : 'puff', at)
    }
    const milestone = milestoneOf(ev)
    if (milestone) {
      const at = this.building.board.root.getWorldPosition(new THREE.Vector3())
      at.y += 1.6
      this.effects.burst('confetti', at, milestone === 'mission-completed' ? 90 : 40)
    }
  }

  select(target: SceneTarget | null): void {
    const root = target ? findRoot(this.building.scene, target) : null
    this.selection.select(target && root ? { target, root } : null, this.loop.now)
  }

  /** Glides the camera to an agent, a machine, or anything selectable; closer in on an agent seen from far away. */
  focus(target: SceneTarget): void {
    const root = findRoot(this.building.scene, target)
    if (!root) return
    const to = root.getWorldPosition(new THREE.Vector3())
    to.y = 0
    this.rig.glideTo(to, target.kind === 'agent' ? Math.min(this.rig.viewHeight, 10) : this.rig.viewHeight, this.loop.now, this.calm)
  }

  goToRoom(index: number): void {
    const room = this.building.rooms[index]
    if (room) this.rig.glideTo(room.center.clone(), this.rig.viewHeight, this.loop.now, this.calm)
  }

  /** Frees everything the scene uploaded, then the renderer and its context: a new decade builds all of it again. */
  dispose(): void {
    this.loop.stop()
    this.tags.dispose()
    this.petBubbles.dispose()
    this.petVoice.dispose()
    this.input.dispose()
    this.cleanup.forEach((f) => f())
    disposeDrawn(this.pipeline.renderer, this.building.scene)
    disposeGlyphs()
    this.city.dispose()
    this.pipeline.dispose()
  }

  // ---- internals -------------------------------------------------------------------------------------------------

  /** A right click on an agent's figure or tag: selected, then its actions. */
  private menu(target: SceneTarget, clientX: number, clientY: number): void {
    this.select(target)
    this.callbacks.onSelect(target)
    this.callbacks.onContextMenu(target, clientX, clientY)
  }

  private bindInput(): SceneInput {
    return new SceneInput(this.canvas, {
      pick: (x, y) => this.picker.pick(x, y),
      click: (hit) => {
        // The robot and the pets are not selected: the robot is a shortcut, the pets only have a name.
        this.select(hit && (hit.target.kind === 'robot' || hit.target.kind === 'pet') ? null : (hit?.target ?? null))
        this.callbacks.onSelect(hit?.target ?? null)
      },
      pan: (dx, dy) => this.rig.pan(dx, dy, this.canvas.clientHeight),
      hover: (hit, x, y) => this.hover(hit, x, y),
      unhover: () => {
        this.selection.hover(null)
        this.callbacks.onHover(null, 0, 0)
      },
      open: (target) => this.callbacks.onOpen?.(target),
      contextMenu: (hit, x, y) => this.menu(hit.target, x, y),
      zoom: (out) => this.rig.zoom(out),
      resize: () => this.resize(),
    })
  }

  private hover(hit: Hit | null, clientX: number, clientY: number): void {
    this.canvas.style.cursor = hit ? 'pointer' : 'default'
    this.selection.hover(hit)
    this.callbacks.onHover(hit?.target ?? null, clientX, clientY)
  }

  private resize(): void {
    this.rig.lowRes = this.pipeline.resize(this.canvas.clientWidth || window.innerWidth, this.canvas.clientHeight || window.innerHeight)
  }

  /** The scenery this frame: machines, the city and the sky, the blur and haze of distance, the table, the board. */
  private scenery(t: number, dt: number): void {
    for (const m of this.building.machines.values()) m.update(t, dt)
    const clock = new Date()
    this.city.update(t, dt, clock.getHours() + clock.getMinutes() / 60)
    this.sky.update(dt, clock.getHours() + clock.getMinutes() / 60, this.calm, this.loop.fps)
    // Zoomed in on the office everything is sharp; zoomed out, the city blurs and clouds pass in front of it.
    const away = THREE.MathUtils.smoothstep(this.rig.viewHeight, 16, 48)
    this.pipeline.setBlur(away)
    this.city.setDistance(away, this.building.scene.fog as THREE.Fog | null)
    this.building.table.update(t)
    for (const led of this.building.ambience) led.visible = Math.sin(t * led.userData.blink * 3) > -0.3
    this.boardTimer += dt
    if (this.boardTimer > 0.25) {
      this.building.board.draw(t)
      this.boardTimer = 0
    }
  }

  /**
   * The browser tests run the pets' clock ahead (canvas data `petAdvance`, in seconds, at most 600): their naps would
   * take minutes, and a slow frame rate stretches them further, since a frame counts 0.1 s at most. The steps are those
   * of a 10 fps frame; what the pets then do is written at once and the request removed, in the same frame.
   */
  private petsAhead(t: number): void {
    const asked = Number(this.canvas.dataset?.petAdvance)
    if (!this.canvas.dataset || !Number.isFinite(asked)) return
    delete this.canvas.dataset.petAdvance
    const steps = Math.round(Math.min(600, Math.max(0, asked)) / 0.1)
    for (let i = 0; i < steps; i++) this.mascots.update(t, 0.1)
    this.canvas.dataset.pets = this.mascots.states()
  }

  /** Where the robot, the pets, the agents and who speaks are, four times a second, on the canvas for the browser tests (data only). */
  private markMascots(dt: number): void {
    this.markTimer += dt
    if (this.markTimer < 0.25 || !this.canvas.dataset) return
    this.markTimer = 0
    const m = this.mascots
    const named = { robot: m.robot, cat: m.cat, dog: m.dog, library: this.building.library.root }
    this.canvas.dataset.mascots = screenMarks(this.canvas, this.rig.camera, this.mark, named)
    this.canvas.dataset.pets = m.states()
    Object.assign(this.canvas.dataset, officeMarks(this.crew.figures.values(), this.loop.now), { remote: [...this.crew.seating.remote.keys()].join(' ') })
  }

  private frame(t: number, dt: number): void {
    this.rig.update(t, dt, this.input.keys)
    this.crew.update(t, dt, (now) => this.celebration.play(now, this.calm))
    if (this.calm) this.petBubbles.hide()
    else {
      this.mascots.update(t, dt)
      this.petsAhead(t)
      this.petBubbles.place(this.rig.camera, this.mascots.speech())
    }
    this.scenery(t, dt)
    this.selection.update(t, this.calm)
    this.effects.update(dt)
    this.moodTimer += dt
    if (this.moodTimer > 5) {
      this.lights.mood(this.crew.night)
      this.moodTimer = 0
    }
    this.markMascots(dt)
    this.pipeline.render(this.building.scene, this.rig.camera)
    this.tags.place(this.rig.camera, (id) => this.crew.figures.get(id)?.root, (id) => this.linesOf(id, t))
  }
}
