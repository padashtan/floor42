import { reactive } from 'vue'
import { next, type DayNight } from '../scene/dayNight'

/** Day / night choice of the scenery (decor), kept in this browser like the other display choices. */
export const DAY_NIGHT_STORAGE = 'floor42.dayNight'

function read(): DayNight {
  try {
    const v = localStorage.getItem(DAY_NIGHT_STORAGE)
    return v === 'day' || v === 'night' ? v : 'auto'
  } catch {
    return 'auto'
  }
}

export const dayNight = reactive<{ mode: DayNight }>({ mode: read() })

export function toggleDayNight(): void {
  dayNight.mode = next(dayNight.mode)
  try {
    localStorage.setItem(DAY_NIGHT_STORAGE, dayNight.mode)
  } catch {
    // The choice then lasts until the page closes.
  }
}
