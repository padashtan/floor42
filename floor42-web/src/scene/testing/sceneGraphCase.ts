import { afterEach, beforeEach, describe as group, expect, it, vi } from 'vitest'
import { Figure } from '../figures'
import { lookOf, uniformOf } from '../identity'
import { OfficeScene } from '../OfficeScene'
import { applyTheme, type ThemeId } from '../palette'
import { fakeBrowser, graphOf, QUIET } from './fakeBrowser'
import { digest, seededRandom } from './sceneDigest'

/**
 * Characterisation of the whole scene OfficeScene draws, decade by decade: built on a fake browser (a canvas that
 * records its painting, a counting WebGL context), with three agents, a selection and two frames run, so the fog, the
 * lights as the hour tints them, the selection ring, the effects, the figures, the city and the sky are all in the
 * digest (sceneDigest.ts: every parameter of every material, lights with their reach and shadows). A refactoring must
 * leave every line as it is; a change of what is drawn updates them on purpose
 * (`npx vitest run -u src/scene/sceneGraph`).
 *
 * One test file per decade (sceneGraph.<decade>.test.ts) calls this, so Vitest builds the four scenes on four workers
 * instead of one after the other: the slowest file of the whole suite was this one.
 */
export function sceneGraphOf(theme: ThemeId): void {
  group('the scene graph of each decade', () => {
  beforeEach(() => {
    // Every draw of Math.random is seeded, except three.js's uuids: how many objects a part makes then changes nothing.
    const next = seededRandom(7)
    vi.spyOn(Math, 'random').mockImplementation(() => (new Error().stack?.includes('generateUUID') ? 0.5 : next()))
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 1, 21, 30))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    applyTheme('1990s')
  })

    it(`${theme}: the whole office scene, two frames in`, { timeout: 60_000 }, () => {
      const browser = fakeBrowser()
      const scene = new OfficeScene(browser.canvas(), undefined, QUIET, theme)
      scene.setAgents([
        { id: 'dev-1', name: 'Ada', status: 'WORKING' },
        { id: 'dev-2', name: 'Bob', status: 'AVAILABLE' },
        { id: 'dev-3', name: 'Cy', status: 'WAITING_FOR_DIRECTOR' },
      ])
      scene.select({ kind: 'agent', id: 'dev-1' })
      browser.frames(2)
      const figure = new Figure('agent-1', uniformOf('agent-1'), lookOf('agent-1'))
      expect({ scene: digest(graphOf(scene)), figure: digest(figure.root) }).toMatchSnapshot()
      scene.dispose()
    })
})
}
