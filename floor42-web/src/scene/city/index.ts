import * as THREE from 'three'
import { disposeTree, ResourceCache } from '../resources'
import { buildings } from './buildings'
import { type Flicker, NeonSigns } from './citySigns'
import { Birds } from './birds'
import { type CityContext, DEPTH, seeded, type Weather } from './kit'
import { addRoofs, roofKit } from './roofs'
import { shops } from './shops'
import { carts, fire, type Puff, smoke } from './streetScenes'
import { blocksAround, ourTower, streets, type StreetLights } from './streets'
import { type Flasher, Traffic } from './traffic'
import { plantTrees } from './trees'
import { Walkers } from './walkers'
import { CityWeather } from './weather'

export { DEPTH, type Weather } from './kit'

/**
 * The city far below the office, built part by part (streets, buildings and roofs, trees, shops and signs, traffic,
 * street scenes, passers-by, birds, weather), each from the same deterministic draws in the same order, so the city is
 * the same on every screen. Scenery only: no shadows, no outline, never picked. {@link dispose} frees all of it.
 */
export class City {
  readonly root = new THREE.Group()

  private readonly cache = new ResourceCache()
  private readonly lights: StreetLights
  private signalPhase = -1
  private readonly lit: THREE.MeshBasicMaterial[] = []
  private readonly signs: Flicker[] = []
  private readonly glows: Map<string, THREE.MeshBasicMaterial>
  private readonly flashers: Flasher[] = []
  private readonly traffic: Traffic
  private readonly puffs: Puff[]
  private readonly walkers: Walkers
  private readonly birds: Birds
  private readonly sky: CityWeather

  /** @param cx,cz centre of the tower's footprint */
  constructor(cx: number, cz: number, width: number, depth: number) {
    const ctx: CityContext = { root: this.root, rnd: seeded(11), cache: this.cache, cx, cz, y0: -DEPTH, unit: new THREE.BoxGeometry(1, 1, 1) }
    const tower = ourTower(ctx, width, depth)
    this.lit.push(tower.wall)
    for (const mesh of tower.signs) this.signs.push({ mesh, phase: 0 })
    const blocks = blocksAround(cx, cz)
    const street = streets(ctx, blocks)
    this.lights = street
    const roofs = roofKit()
    const built = buildings(ctx, blocks, street.trees, roofs, (m) => this.lit.push(m))
    addRoofs(ctx, roofs)
    plantTrees(ctx, street.trees)
    const neon = new NeonSigns()
    this.glows = neon.glows
    shops(ctx, built.lots, built.stepped, () => neon.build(ctx, built.fronts, built.solids, this.signs))
    this.traffic = new Traffic(ctx, this.flashers)
    this.puffs = fire(ctx, built.fireSite, this.traffic.kit, this.flashers)
    carts(ctx)
    this.walkers = new Walkers(ctx, blocks)
    this.birds = new Birds(ctx)
    this.sky = new CityWeather(ctx)
    this.setWeather(this.sky.random())
    // Scenery: no shadows, no outline, never picked by the director's clicks.
    this.root.traverse((o) => {
      o.castShadow = false
      o.receiveShadow = false
      o.userData.noOutline = true
      o.raycast = () => {}
    })
  }

  get weather(): Weather {
    return this.sky.weather
  }

  /**
   * Distance fades the city into the sky, like haze far below: the office, much closer to the camera, is untouched.
   */
  static fog(background: number): THREE.Fog {
    return new THREE.Fog(background, 85, 150)
  }

  /**
   * How far the view is zoomed out, 0 (on the office) to 1 (the whole city): the haze thickens and high clouds drift
   * past, keeping clear of the office's own footprint.
   */
  setDistance(k: number, fog: THREE.Fog | null): void {
    this.sky.setDistance(k, fog)
  }

  setWeather(w: Weather): void {
    this.sky.set(w, this.walkers.umbrellas)
  }

  /** @param hour the director's local hour, minutes as a fraction: windows light up in the evening */
  update(t: number, dt: number, hour: number): void {
    if (this.sky.due(dt)) this.setWeather(this.sky.random())
    const dark = hour < 7 || hour >= 19.5
    for (const m of this.lit) m.color.setScalar(dark ? 0.55 : 1)
    // The light the neon throws on the walls shows at night, hardly by day.
    for (const m of this.glows.values()) m.opacity = dark ? 0.75 : 0.3
    this.traffic.update(dt, this.weather)
    this.streetLights(t, dark)
    for (const f of this.flashers) {
      ;(f.mesh.material as THREE.MeshBasicMaterial).color.setHex(Math.floor(t * f.rate) % 2 ? f.colors[0] : f.colors[1])
    }
    // Neon flickers now and then.
    for (const s of this.signs) {
      const flicker = Math.sin(t * 13 + s.phase * 7) > 0.97 ? 0.4 : 1
      ;(s.mesh.material as THREE.MeshBasicMaterial).color.setScalar(flicker)
    }
    smoke(this.puffs, t)
    this.walkers.update(t, this.weather === 'rain')
    this.birds.update(t)
    this.sky.update(dt)
  }

  /** Street lamps light up in the evening; traffic lights swap every few seconds. */
  private streetLights(t: number, dark: boolean): void {
    this.lights.bulbs.color.setHex(dark ? 0xffe9a0 : 0x8a8676)
    const go = Math.floor(t / 6) % 2
    if (go === this.signalPhase) return
    this.signalPhase = go
    const signals = this.lights.signals
    for (let i = 0; i < signals.count; i++) signals.setColorAt(i, new THREE.Color(i % 2 === go ? 0x3bff6a : 0xff3b30))
    signals.instanceColor!.needsUpdate = true
  }

  /** Frees every geometry, material and texture of the city. */
  dispose(): void {
    disposeTree(this.root)
    this.cache.dispose()
  }
}
