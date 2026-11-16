/** The director's choice of the scenery's hour: the clock, or a fixed day or night. Decor only (docs: sheet "day / night"). */
export type DayNight = 'auto' | 'day' | 'night'

const ORDER: DayNight[] = ['auto', 'day', 'night']
export const EASE_SECONDS = 1.5

export function next(mode: DayNight): DayNight {
  return ORDER[(ORDER.indexOf(mode) + 1) % ORDER.length]
}

/** The hour the scenery reads: 12 for day, 22 for night, the clock's hour with its minutes as a fraction otherwise. */
export function sceneHour(mode: DayNight, clock: Date): number {
  if (mode === 'day') return 12
  if (mode === 'night') return 22
  return clock.getHours() + clock.getMinutes() / 60
}

/** The hour between `from` and `to` at `progress` (0 to 1), the short way round the 24 h dial; `to` at once when `calm`. */
export function eased(from: number, to: number, progress: number, calm: boolean): number {
  if (calm || progress >= 1) return to
  const u = Math.min(1, Math.max(0, progress))
  let d = (((to - from) % 24) + 24) % 24
  if (d > 12) d -= 24
  return (((from + d * u) % 24) + 24) % 24
}
