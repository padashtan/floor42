import * as THREE from 'three'
import type { AgentStatus } from '../api/types'
import type { SceneTarget } from './kit'
import { glyphFor } from './mapping'

export interface AgentPresence {
  id: string
  name?: string
  status: AgentStatus
  suspended?: boolean
  /** Starred by the director as a core member: a star before the name on its tag. */
  core?: boolean
  /** Worked on a mission still in progress: stays at its desk, idle, between two sessions (presence.ts). */
  resident?: boolean
  /** Drawn as working from home today (remote.ts): the sign's word; its chair stays empty. Decor, not a journal fact. */
  remote?: string
}

/** Short words for the tag above an agent: what the journal says it is doing, nothing more. */
const TAG: Partial<Record<AgentStatus, string>> = {
  WORKING: 'working',
  EXECUTING: 'running a command',
  REVIEWING: 'reviewing',
  IN_COMMITTEE: 'in committee',
  IN_RETRO: 'in the retrospective',
  IN_MEETING: 'in the meeting',
  ASSIGNED: 'assigned',
  WAITING_FOR_AUTHORIZATION: 'waiting for you',
  WAITING_FOR_DIRECTOR: 'waiting for you',
  PAUSED: 'paused',
}

/** What a tag shows under the name this frame: the live activity line, a joke, the agent's own last words. */
export interface TagLines {
  doing: { text: string; tone: string } | null
  joke: string
  said: string
  /** Its voice was recorded a moment ago at a committee or a meeting. */
  speaking: boolean
}

/** What a click on a tag does: the same as on the figure. */
export interface TagActions {
  select(target: SceneTarget): void
  open(target: SceneTarget): void
  menu(target: SceneTarget, clientX: number, clientY: number): void
}

/**
 * The name tags that follow the agents on screen, in an HTML layer over the canvas; each tag's colour follows the same
 * glyph as the bubble. Without a layer (a test), there are none.
 */
export class NameTags {
  private readonly tags = new Map<string, HTMLDivElement>()
  private readonly layer: HTMLElement | undefined
  private readonly canvas: HTMLCanvasElement
  private readonly actions: TagActions
  private readonly remote: ReadonlyMap<string, { x: number; z: number }>

  /** `remote`: where each remote agent's empty chair is (the crew's), for the sign it gets in place of a figure. */
  constructor(layer: HTMLElement | undefined, canvas: HTMLCanvasElement, actions: TagActions, remote: ReadonlyMap<string, { x: number; z: number }>) {
    this.remote = remote
    this.layer = layer
    this.canvas = canvas
    this.actions = actions
  }

  private create(id: string): HTMLDivElement {
    const el = document.createElement('div')
    el.className = 'name-tag'
    const target: SceneTarget = { kind: 'agent', id }
    el.addEventListener('click', () => this.actions.select(target))
    el.addEventListener('dblclick', () => this.actions.open(target))
    el.addEventListener('contextmenu', (e) => {
      e.preventDefault()
      this.actions.menu(target, e.clientX, e.clientY)
    })
    this.layer!.appendChild(el)
    this.tags.set(id, el)
    return el
  }

  /** The agent's name and state, written afresh. */
  show(a: AgentPresence): void {
    if (!this.layer) return
    const el = this.tags.get(a.id) ?? this.create(a.id)
    el.dataset.tone = a.suspended ? 'off' : glyphFor(a.status)
    el.textContent = ''
    const name = document.createElement('b')
    name.textContent = (a.core ? '★ ' : '') + (a.name ?? a.id)
    const what = document.createElement('span')
    what.textContent = a.suspended ? 'suspended' : (a.remote ?? TAG[a.status] ?? 'idle')
    const doing = document.createElement('i')
    doing.className = 'doing'
    const joke = document.createElement('q')
    joke.className = 'joke'
    const said = document.createElement('q')
    said.className = 'said'
    el.append(name, what, doing, said, joke)
  }

  remove(id: string): void {
    this.tags.get(id)?.remove()
    this.tags.delete(id)
  }

  /** Each tag over its figure's head, with this frame's lines; hidden off screen and for agents out of the office. */
  place(camera: THREE.Camera, figureOf: (id: string) => THREE.Object3D | undefined, linesOf: (id: string) => TagLines): void {
    if (!this.layer || this.tags.size === 0) return
    const rect = this.canvas.getBoundingClientRect()
    const p = new THREE.Vector3()
    for (const [id, el] of this.tags) {
      const figure = figureOf(id)
      const chair = this.remote.get(id)
      if (!figure) continue
      if (!figure.visible && !chair) {
        el.style.display = 'none'
        continue
      }
      write(el, linesOf(id))
      if (chair) p.set(chair.x, 1.5, chair.z)
      else p.set(figure.position.x, figure.position.y + 2.25, figure.position.z)
      p.project(camera)
      const visible = p.x > -1.05 && p.x < 1.05 && p.y > -1.05 && p.y < 1.05
      el.style.display = visible ? '' : 'none'
      if (!visible) continue
      const x = Math.round(rect.left + ((p.x + 1) / 2) * rect.width)
      const y = Math.round(rect.top + ((1 - p.y) / 2) * rect.height)
      el.style.transform = `translate(${x}px, ${y}px) translate(-50%, -100%)`
    }
  }

  dispose(): void {
    for (const el of this.tags.values()) el.remove()
    this.tags.clear()
  }
}

/** Writes a tag's lines, touching the page only where they changed. */
function write(el: HTMLElement, lines: TagLines): void {
  const doing = el.querySelector<HTMLElement>('i.doing')
  if (doing && doing.textContent !== (lines.doing?.text ?? '')) {
    doing.textContent = lines.doing?.text ?? ''
    doing.dataset.tone = lines.doing?.tone ?? ''
  }
  const joke = el.querySelector<HTMLElement>('q.joke')
  if (joke && joke.textContent !== lines.joke) joke.textContent = lines.joke
  if ((el.dataset.speaking === '1') !== lines.speaking) el.dataset.speaking = lines.speaking ? '1' : ''
  const said = el.querySelector<HTMLElement>('q.said')
  if (said && said.textContent !== lines.said) said.textContent = lines.said
}
