import type { Page } from '@playwright/test'

/** Where the scene says an agent is on the floor (canvas data `office`, four times a second); null if it has no figure. */
export async function whereIs(page: Page, agent: string): Promise<{ visible: boolean; x: number; z: number } | null> {
  const raw = await page.locator('canvas.view').getAttribute('data-office')
  const mark = (raw ?? '').split(';').find((m) => m.startsWith(agent + ':'))
  if (!mark) return null
  const [, shown, xz] = mark.split(':')
  const [x, z] = xz.split(',').map(Number)
  return { visible: shown === '1', x, z }
}

import { CONFERENCE, DESKS, STATIONS } from '../src/scene/floorPlan'

/** The conference table, and the seat south of each desk where an agent sits (floorPlan.ts, in world units). */
export const CONFERENCE_AT = CONFERENCE
export const DESK_SEATS = DESKS.map((d) => ({ x: d.x, z: d.z + 0.75 }))

/** Whether the agent sits at one of the desk seats, not at the elevator's landing or in a corridor (radius 0.5). */
export const atADesk = (p: { x: number; z: number }): boolean => DESK_SEATS.some((d) => near(p, d, 0.5))

/** Whether it sits at a lounge seat or at the chat table beside it (floorPlan.ts STATIONS), where idle agents spend the day. */
export const atBreak = (p: { x: number; z: number }): boolean => [...STATIONS.lounge, ...STATIONS.chat].some((d) => near(p, d, 0.5))

export const near = (p: { x: number; z: number }, at: { x: number; z: number }, radius: number): boolean =>
  Math.hypot(p.x - at.x, p.z - at.z) <= radius
