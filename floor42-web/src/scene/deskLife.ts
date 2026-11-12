import type * as THREE from 'three'
import type { Hands } from './life'

/** What the desk animation reads of a figure: its limbs and what the journal says it does. */
export interface DeskState {
  armL: THREE.Group
  armR: THREE.Group
  head: THREE.Group
  busy: boolean
  hands: Hands
  /** Idle between two sessions: nothing to type, so nothing is typed (presence.ts). */
  resting: boolean
  seed: number
}

const REST_CYCLE = 18

/**
 * The arms and head at the desk. Fast typing or a sheet held up only while a tool runs; thinking or reviewing, a short
 * burst of keys now and then; resting (idle), the hands off the keys, eyes on the screen and, every 18 seconds, a sip
 * of coffee. Returns whether the mug is in hand.
 */
export function deskPose(s: DeskState, t: number, calm: boolean): boolean {
  const { armL, armR, head } = s
  if (s.busy && s.hands === 'read') {
    armL.rotation.x = -1.25
    // The right hand turns a page every few seconds.
    const flip = calm ? 0 : Math.max(0, Math.sin((t + s.seed) * 1.3)) ** 6
    armR.rotation.x = -0.9 - flip * 0.5
    head.rotation.x = 0.28
  } else if (s.busy) {
    const a = calm ? 0 : 0.09
    armL.rotation.x = -1.15 + Math.sin(t * 15 + s.seed) * a
    armR.rotation.x = -1.15 + Math.sin(t * 13.5 + 1) * a
  } else if (s.resting) {
    armL.rotation.x = armR.rotation.x = -0.55
    head.rotation.x = 0.12
    const c = (t + s.seed * 3) % REST_CYCLE
    if (calm || c < 9 || c > 14) return false
    armL.rotation.x = -0.35
    armR.rotation.x = -0.9 - Math.sin(((c - 9) / 5) * Math.PI) * 1.4
    return true
  } else {
    const burst = calm ? 0 : Math.max(0, Math.sin(t * 0.7 + s.seed)) ** 8
    armL.rotation.x = -1.05 + Math.sin(t * 12 + s.seed) * 0.07 * burst
    armR.rotation.x = -1.05 + Math.sin(t * 11 + 2) * 0.07 * burst
  }
  return false
}
