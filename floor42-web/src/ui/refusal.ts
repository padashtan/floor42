import { tr } from '../i18n'

/**
 * A refusal of the daemon for the banner: its stable code, then the screen's text for that code when the catalogues
 * have one (`refusal.<CODE>`), else the daemon's own message, which may name what only the daemon knows.
 */
export function refusalText(code: string, message: string): string {
  return `${code}: ${tr(`refusal.${code}`, message)}`
}
