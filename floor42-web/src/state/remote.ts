import { computed, reactive } from 'vue'
import { residentsOf } from '../scene/presence'
import { dayKey, remoteSet } from '../scene/remote'
import { office } from './office'

/** "Remote today" (decor): the director's switch and the day, kept in this browser like the other display choices. */
const KEY = 'floor42.remoteDays'
/** Browser tests only: the agents to show remote, comma separated, instead of the draw. */
const FORCE = 'floor42.remote.force'

function read(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export const remote = reactive({ on: read(KEY) !== '0', day: dayKey(new Date()) })

/** The day moves on at midnight: called on the page's clock. */
export function refreshDay(): void {
  const d = dayKey(new Date())
  if (d !== remote.day) remote.day = d
}

export function toggleRemote(): void {
  remote.on = !remote.on
  try {
    localStorage.setItem(KEY, remote.on ? '1' : '0')
  } catch {
    // The choice then lasts until the page closes.
  }
}

/** The agents drawn remote now. */
export const remoteIds = computed(() =>
  remoteSet(office.agents.map((a) => a.id), remote.day, remote.on, (read(FORCE) ?? '').split(',').filter(Boolean)),
)

/** The agents of a mission in progress who are drawn remote: one webcam tile each. */
export const remoteTiles = computed(() => {
  const resident = residentsOf(office.missions)
  return office.agents.filter((a) => remoteIds.value.has(a.id) && resident.has(a.id))
})
