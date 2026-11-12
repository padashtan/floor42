import { afterEach, describe, expect, it, vi } from 'vitest'
import { Elevator } from './elevator'
import { Figure } from './figures'
import { fakeDocument } from './testing/sceneDigest'

/**
 * Characterisation of a ride, frame by frame at 20 frames a second: one agent brought up from the street while another
 * waits to be taken down. Each line: the cabin's height, how far the doors stand open, and each rider's height,
 * visibility and pose; `done` marks the frame a ride ended.
 */
describe('the glass elevator', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('brings one agent up, then takes another down, in turn', () => {
    vi.stubGlobal('document', fakeDocument().document)
    const lift = new Elevator(10, 4)
    const up = new Figure('dev-1', 0x336699, 1)
    const down = new Figure('dev-2', 0x993366, 2)
    down.placeAt(lift.landing, 0, 'stand')
    const events: string[] = []
    lift.bringUp(up, () => events.push('up done'))
    lift.takeDown(down, () => events.push('down done'))
    const r = (v: number) => Math.round(v * 100) / 100
    const lines: string[] = []
    for (let frame = 0; frame < 220; frame++) {
      lift.update(0.05)
      const cabin = lift.root.children.at(-1)!
      const door = cabin.children.at(-1)!
      const rider = (f: Figure) => `${r(f.root.position.y)} ${f.root.visible ? 'shown' : 'hidden'} ${f.pose}`
      lines.push(`${frame}: cabin ${r(cabin.position.y)} door ${r(door.position.z)} | ${rider(up)} | ${rider(down)} ${events.splice(0).join(', ')}`)
    }
    expect(lift.busyWith(up) || lift.busyWith(down)).toBe(false)
    expect(lines.join('\n')).toMatchSnapshot()
  })
})
