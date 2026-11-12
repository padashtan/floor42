import { dateLocale } from '../i18n'

/** A moment as the time of day, in the screen's language. */
export const clock = (at: string) => new Date(at).toLocaleTimeString(dateLocale())
/** A moment with its date, in the screen's language. */
export const stamp = (at: string) => new Date(at).toLocaleString(dateLocale())
