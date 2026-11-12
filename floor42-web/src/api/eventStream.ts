import type { EventView } from './types'

export type StreamState = 'connecting' | 'live' | 'reconnecting'

/**
 * Live events from the daemon, with automatic reconnection (exponential backoff, capped). A missed message is not a
 * lost fact: on every (re)connection the caller reloads state through the REST API.
 */
export class EventStream<T = EventView> {
  private socket: WebSocket | null = null
  private retry = 0
  private timer: number | undefined
  private closed = false

  private readonly onEvent: (event: T) => void
  private readonly onState: (state: StreamState) => void
  private readonly onConnected: () => void
  private readonly path: string

  constructor(
    onEvent: (event: T) => void,
    onState: (state: StreamState) => void,
    onConnected: () => void,
    path = '/ws/events',
  ) {
    this.onEvent = onEvent
    this.onState = onState
    this.onConnected = onConnected
    this.path = path
  }

  open(): void {
    this.closed = false
    this.connect()
  }

  close(): void {
    this.closed = true
    window.clearTimeout(this.timer)
    this.socket?.close()
  }

  private connect(): void {
    this.onState(this.retry === 0 ? 'connecting' : 'reconnecting')
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${scheme}://${location.host}${this.path}`)
    this.socket = socket
    socket.onopen = () => {
      this.retry = 0
      this.onState('live')
      this.onConnected()
    }
    socket.onmessage = (message) => {
      const data = JSON.parse(message.data as string)
      if (data.type !== 'hello') this.onEvent(data as T)
    }
    socket.onclose = () => {
      if (this.closed) return
      this.onState('reconnecting')
      const delay = Math.min(10_000, 500 * 2 ** this.retry++)
      this.timer = window.setTimeout(() => this.connect(), delay)
    }
  }
}
