/**
 * The top bar's room on screen, as style.css lays it out: the bar starts at the left edge and, above
 * {@link LOG_BESIDE_ABOVE} pixels, stops before the event log in the top right corner (below that the log moves down,
 * beside the crew). Its buttons never shrink, nor do the measured numbers (quota gauges, spend: `.measure`), whose
 * text must stay readable; the other status badges (build, daemon, mission) shrink to {@link BADGE_MIN} with an
 * ellipsis, their full text in their tooltip. The test checks, for every width from 1280 to 2560 and both languages,
 * that the buttons, the measures at their longest and the badges at their minimum fit: no button leaves the screen or
 * slides under the log, no measured number is cut. Keep these numbers in step with style.css (`.brand`, `.log`,
 * `.brand > .shrink`, `.brand > .measure`).
 */
export const EDGE = 8
export const LOG_WIDTH = 400
/** style.css moves the log down at `max-width: 1700px` (1650 before the measured numbers stayed whole): wider than this, it sits beside the bar. */
export const LOG_BESIDE_ABOVE = 1700
/** The bar's padding (6px 10px) and border (2px), both sides. */
export const BAR_CHROME = 24
export const GAP = 8
/** A shrunk status badge keeps a few letters and its colour. */
export const BADGE_MIN = 40
/** Measured in Chrome with the bar's font (Bahnschrift 15 px): at most 8.6 px a letter; rounded up for safety. */
export const CHAR = 9
/** A button's padding (2px 6px) and border (1px), both sides, rounded up. */
export const BUTTON_CHROME = 16
/** "FLOOR42", bold and spaced: measured 94 px. */
export const BRAND = 100

/**
 * Where the direct buttons show their icon alone (the label stays in the tooltip and for screen readers; every action
 * is also in its menu), as the model below finds the label version too wide for the measured numbers: up to
 * {@link COMPACT_UPTO}, and, with the log beside the bar, from {@link LOG_BESIDE_ABOVE} + 1 to {@link COMPACT_BAND_END}
 * (both languages; French is the widest). Everywhere else the label sits beside the icon, the badges shrinking with
 * an ellipsis. style.css repeats the three numbers; where the labels show and the bar is tight (up to
 * {@link HINT_HIDDEN_UPTO}) the session-time hint gives way.
 */
export const COMPACT_UPTO = 1504
export const COMPACT_BAND_END = 1912
export const HINT_HIDDEN_UPTO = 2106

export const isCompact = (viewport: number): boolean =>
  viewport <= COMPACT_UPTO || (viewport > LOG_BESIDE_ABOVE && viewport <= COMPACT_BAND_END)

/** A direct button's text at this window width: the icon (counted as one square) alone when compact, else with its label. */
export function directText(_key: string | undefined, label: string, viewport: number): string {
  return isCompact(viewport) ? '□' : `□ ${label}`
}

/** The widest the bar may be at this window width. */
export function barBudget(viewport: number): number {
  return viewport - 2 * EDGE - (viewport > LOG_BESIDE_ABOVE ? LOG_WIDTH + EDGE : 0)
}

/** A button's width for its text, never under what the browser draws. */
export function buttonWidth(text: string): number {
  return Math.ceil([...text].length * CHAR + BUTTON_CHROME)
}

/**
 * The bar's narrowest: the brand, every button and every measure (its text as written, a button's chrome) at full
 * width, the badges at their minimum, the gaps between.
 */
export function barMinimum(buttons: readonly string[], badges: number, measures: readonly string[] = []): number {
  const items = 1 + buttons.length + measures.length + badges
  const whole = [...buttons, ...measures].reduce((n, b) => n + buttonWidth(b), 0)
  return BRAND + whole + badges * BADGE_MIN + (items - 1) * GAP + BAR_CHROME
}

/** Whether every button and measured number of the bar stays on screen and whole, clear of the log, at this width. */
export function barFits(viewport: number, buttons: readonly string[], badges: number, measures: readonly string[] = []): boolean {
  return barMinimum(buttons, badges, measures) <= barBudget(viewport)
}

/** The bar wraps on a narrow screen: the waiting banner sits under it, the crew under both (--waiting-top, --banner-h, --crew-top). */
export function placeUnderBar(bar: HTMLElement, waitingBox: HTMLElement | undefined): void {
  const css = document.documentElement.style
  const top = Math.ceil(bar.getBoundingClientRect().bottom) + 6
  const box = waitingBox?.getBoundingClientRect()
  css.setProperty('--waiting-top', `${top}px`)
  css.setProperty('--banner-h', `${box?.height ? Math.ceil(box.height) + 6 : 0}px`)
  css.setProperty('--crew-top', `${box?.height ? Math.ceil(box.bottom) + 6 : top}px`)
}

/** A menu's left edge under its button: moved left when it would run past the window's right edge. */
export function menuLeft(buttonLeft: number, menuWidth: number, viewport: number): number {
  return Math.max(EDGE, Math.min(buttonLeft, viewport - EDGE - menuWidth))
}
