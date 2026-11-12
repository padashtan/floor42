// The director's own terminal (ADR-0041): the plain rules of the panel, apart from xterm.js and Vue so they can be tested.
import type { ProjectView, TerminalView } from '../api/types'

/** The screen's WebSocket: same host as the page, the single-use ticket in the query. */
export function terminalUrl(loc: { protocol: string; host: string }, id: string, ticket: string): string {
  const scheme = loc.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${loc.host}/ws/terminal/${encodeURIComponent(id)}?ticket=${encodeURIComponent(ticket)}`
}

/** What the director typed or pasted, passed on unchanged. */
export function inputMessage(data: string): string {
  return JSON.stringify({ type: 'input', data })
}

export function resizeMessage(columns: number, rows: number): string {
  return JSON.stringify({ type: 'resize', columns, rows })
}

/** A text frame from the daemon: the cost Claude Code reported, or the end of the program. */
export type TerminalMessage = { type: 'status' | 'ended'; terminal: TerminalView }

export function parseMessage(text: string): TerminalMessage | null {
  try {
    const m = JSON.parse(text) as Partial<TerminalMessage>
    if ((m.type === 'status' || m.type === 'ended') && m.terminal) return m as TerminalMessage
  } catch {
    // not a message of ours
  }
  return null
}

/** Claude Code's own estimate, as it reported it; never invented while it said nothing. */
export function costText(view: TerminalView | null): string | null {
  if (!view || view.costUsd === null || view.costUsd === undefined) return null
  return `$${view.costUsd.toFixed(4)}`
}

/**
 * The projects to offer: the active mission's first, then those missions already used that still exist, without
 * duplicates.
 */
export function projectChoices(active: string | null, projects: ProjectView[]): string[] {
  const out: string[] = []
  const add = (p: string | null | undefined) => {
    if (p && !out.includes(p)) out.push(p)
  }
  add(active)
  projects.filter((p) => p.exists && !p.office).forEach((p) => add(p.path))
  return out
}

/** Ctrl+Shift+C copies the selection and Ctrl+Shift+V pastes: plain Ctrl+C stays Claude Code's interrupt. */
export function clipboardKey(e: { ctrlKey: boolean; shiftKey: boolean; key: string; type: string }): 'copy' | 'paste' | null {
  if (e.type !== 'keydown' || !e.ctrlKey || !e.shiftKey) return null
  const k = e.key.toLowerCase()
  return k === 'c' ? 'copy' : k === 'v' ? 'paste' : null
}
