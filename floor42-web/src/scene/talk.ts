import { JOKE_EVERY, JOKES, REACTIONS } from './jokes'
import type { AgentPresence } from './labels'

/** Seconds without an event, while working with no tool running, after which an agent counts as thinking. */
const THINKING_AFTER = 6

/** A conversation at the chat table: the agent thinking, and an idle colleague with it, if any. */
export interface Chat {
  thinker: string
  listener: string | null
}

/** What talk needs to know of the office: who is out, who idles in the lounge. */
export interface TalkRules {
  night(): boolean
  /** In the lounge and in the office, not suspended. */
  idle(a: AgentPresence): boolean
}

/**
 * The chat table and the jokes. An agent working without a tool running, and with nothing on the live stream for
 * {@link THINKING_AFTER} seconds, is thinking (its model is writing): it goes to the chat table with a coffee, and an
 * idle colleague joins it; back to its desk on its next event. Nothing here is invented: the quiet is measured, the
 * words are its own. Idle agents tell a joke now and then, from a fixed list, never recorded.
 */
export class Talk {
  /** Pair i uses chat slots 2i (the thinker) and 2i + 1 (the listener). */
  readonly chats = new Map<number, Chat>()
  /** When each agent last had an event on the live stream; the scene's start stands for agents not heard since. */
  readonly lastSign = new Map<string, number>()
  /** The last words each agent wrote in its session (AgentRemarked), shown while it thinks over a coffee. */
  readonly remarks = new Map<string, string>()
  readonly jokes = new Map<string, { text: string; until: number; next: number }>()
  language: 'en' | 'fr' = 'en'
  private readonly rules: TalkRules

  constructor(rules: TalkRules) {
    this.rules = rules
  }

  forget(id: string): void {
    this.jokes.delete(id)
    this.remarks.delete(id)
    this.lastSign.delete(id)
  }

  isThinker(id: string): boolean {
    return [...this.chats.values()].some((c) => c.thinker === id)
  }

  private isListener(id: string): boolean {
    return [...this.chats.values()].some((c) => c.listener === id)
  }

  private thinking(a: AgentPresence | undefined, t: number): boolean {
    return !!a && a.status === 'WORKING' && !a.suspended && !this.rules.night() && t - (this.lastSign.get(a.id) ?? 0) > THINKING_AFTER
  }

  private busy(): Set<string> {
    return new Set([...this.chats.values()].flatMap((c) => [c.thinker, c.listener ?? '']))
  }

  /** Ends, joins and starts conversations; returns the agents who must walk somewhere else. */
  converse(t: number, agents: AgentPresence[], pairs: number): Set<string> {
    const idle = (a: AgentPresence | undefined) => !!a && this.rules.idle(a)
    const moved = new Set<string>()
    this.endChats(t, new Map(agents.map((a) => [a.id, a])), idle, moved)
    for (const a of agents) {
      if (!this.thinking(a, t) || this.busy().has(a.id)) continue
      const pair = [...Array(pairs).keys()].find((p) => !this.chats.has(p))
      if (pair === undefined) break
      const listener = agents.find((b) => idle(b) && !this.busy().has(b.id))
      this.chats.set(pair, { thinker: a.id, listener: listener?.id ?? null })
      moved.add(a.id)
      if (listener) moved.add(listener.id)
    }
    for (const c of this.chats.values()) {
      if (c.listener) continue
      const listener = agents.find((b) => idle(b) && !this.busy().has(b.id))
      if (!listener) continue
      c.listener = listener.id
      moved.add(listener.id)
    }
    return moved
  }

  /** A conversation ends when its thinker no longer thinks; a listener called away leaves it. */
  private endChats(t: number, byId: Map<string, AgentPresence>, idle: (a: AgentPresence | undefined) => boolean, moved: Set<string>): void {
    for (const [pair, c] of [...this.chats]) {
      if (!this.thinking(byId.get(c.thinker), t)) {
        this.chats.delete(pair)
        moved.add(c.thinker)
        if (c.listener) moved.add(c.listener)
      } else if (c.listener && !idle(byId.get(c.listener))) {
        moved.add(c.listener)
        c.listener = null
      }
    }
  }

  /** Idle agents in the lounge tell a joke now and then, each on its own rhythm; one in a conversation answers. */
  tellJokes(t: number, agents: AgentPresence[], free: (a: AgentPresence) => boolean): void {
    for (const a of agents) {
      const listening = this.isListener(a.id)
      const list = (listening ? REACTIONS : JOKES)[this.language]
      if (!free(a)) {
        this.jokes.delete(a.id)
        continue
      }
      const j = this.jokes.get(a.id)
      if (!j) {
        this.jokes.set(a.id, { text: '', until: 0, next: t + 5 + Math.random() * JOKE_EVERY.min })
      } else if (t >= j.next) {
        // Never the joke someone else is telling right now.
        const told = new Set([...this.jokes.values()].filter((x) => x.until > t).map((x) => x.text))
        const fresh = list.filter((x) => !told.has(x))
        j.text = fresh[Math.floor(Math.random() * fresh.length)] ?? list[0]
        j.until = t + JOKE_EVERY.shown
        j.next = listening ? t + 9 + Math.random() * 6 : t + JOKE_EVERY.min + Math.random() * (JOKE_EVERY.max - JOKE_EVERY.min)
      }
    }
  }
}
