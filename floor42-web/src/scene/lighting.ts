import * as THREE from 'three'
import type { Building } from './layout'
import { daylight } from './life'

/** Evening tints the light eases towards. */
const DUSK_SKY = new THREE.Color(0x8a76b0)
const DUSK_GROUND = new THREE.Color(0x3a2a24)
const DUSK_SUN = new THREE.Color(0xffa25a)

/**
 * The light of the hour, from the director's local clock like the city's windows: neutral in the day, a warm sun and a
 * dusky sky in the evening, desk lamps a little stronger. Scenery only; the quota's night dims everything instead.
 */
export class OfficeLights {
  private readonly lights: Building['lights']
  /** The lights as the theme built them, before the evening tint. */
  private readonly base: { sky: THREE.Color; ground: THREE.Color; sun: THREE.Color; points: number[] }

  constructor(lights: Building['lights']) {
    this.lights = lights
    const { hemi, key, points } = lights
    this.base = { sky: hemi.color.clone(), ground: hemi.groundColor.clone(), sun: key.color.clone(), points: points.map((p) => p.intensity) }
  }

  /** The desk lamps go out at night. */
  setNight(on: boolean): void {
    for (const p of this.lights.points) p.visible = !on
  }

  mood(night: boolean, clock = new Date()): void {
    const { hemi, key, points } = this.lights
    if (night) {
      hemi.intensity = 0.35
      key.intensity = 0.2
      return
    }
    const { warmth, level } = daylight(clock.getHours() + clock.getMinutes() / 60)
    hemi.intensity = 1.9 * level
    key.intensity = 2.4 * (1 - 0.25 * warmth)
    hemi.color.copy(this.base.sky).lerp(DUSK_SKY, 0.35 * warmth)
    hemi.groundColor.copy(this.base.ground).lerp(DUSK_GROUND, 0.3 * warmth)
    key.color.copy(this.base.sun).lerp(DUSK_SUN, 0.55 * warmth)
    points.forEach((p, i) => (p.intensity = this.base.points[i] * (1 + 0.3 * warmth)))
  }
}
