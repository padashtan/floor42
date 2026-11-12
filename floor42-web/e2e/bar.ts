import type { Locator, Page } from '@playwright/test'
import { type Action, ACTIONS, type Group } from '../src/ui/actions'
import type { Catalog } from './fixtures'

/**
 * The panel each action of the bar opens (ui/actions.ts). A new action without an entry here fails the panels journey,
 * so the browser tests follow the bar.
 */
export const PANEL_OF: Record<string, string> = {
  desk: '.desk-panel',
  board: '.board-panel',
  progress: '.progress-panel',
  resume: '.resume-panel',
  workshop: '.panel.workshop',
  library: '.library-panel',
  meeting: '.panel.meeting',
  callMeeting: '.director-meeting',
  workflows: '.workflows-panel',
  deliverables: '.deliverables-panel',
  prove: '.prove-panel',
  archive: '.archive-panel',
  tutorial: '.tutorial-card',
  hire: '.hire-panel:not(.core-dismissal)',
  core: '.core-dismissal',
  console: '.api-console',
  costs: '.costs-panel',
  caps: '.caps-panel',
  sandbox: '.sandbox-panel',
  skills: '.skills-panel',
  terminal: '.director-terminal',
  sessions: '.sessions-panel',
  identities: '.identities-panel',
  usage: '.panel.usage',
  settings: '.settings-panel',
}

export const PANEL_ACTIONS: readonly Action[] = ACTIONS.filter((a) => !a.href)

export function menuButton(page: Page, group: Group): Locator {
  return page.locator(`.panel.brand .bar-nav button[data-menu="${group}"]`)
}

export function openMenuPanel(page: Page): Locator {
  return page.locator('.bar-dropdown[role="menu"]')
}

/** An action's entry in its open menu, by its label in the page's language. */
export function menuItem(page: Page, t: Catalog, a: Action): Locator {
  return openMenuPanel(page).locator('[role="menuitem"]', { hasText: t[a.label] }).first()
}

/** Runs an action through its menu: open the menu, click the entry. */
export async function runFromMenu(page: Page, t: Catalog, a: Action): Promise<void> {
  await menuButton(page, a.group).click()
  await menuItem(page, t, a).click()
}

/**
 * Runs an action through the command palette: Ctrl+K, its label, Enter. Returns the label of the option Enter ran
 * when it was not the action typed (the palette ranked something else first), null when it was.
 */
export async function runFromPalette(page: Page, t: Catalog, a: Action): Promise<string | null> {
  await page.keyboard.press('Control+k')
  const input = page.locator('.palette input')
  await input.fill(t[a.label])
  const active = page.locator('.palette li[role="option"][aria-selected="true"] .label')
  const first = ((await active.textContent()) ?? '').replace(/\s*↗$/, '').trim()
  if (first !== t[a.label]) {
    // Choose the action itself, so the journey still opens the right panel; the caller reports the ranking.
    await page
      .locator('.palette li[role="option"]')
      .filter({ has: page.locator('.label', { hasText: new RegExp(`^${escape(t[a.label])}$`) }) })
      .first()
      .click()
    return first
  }
  await page.keyboard.press('Enter')
  return null
}

function escape(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
