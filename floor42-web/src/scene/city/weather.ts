import * as THREE from 'three'
import { type CityContext, DEPTH, pick, SPAN, type Weather } from './kit'
import { cloudTexture } from './textures'

const DROPS = 900
const WEATHERS: Weather[] = ['clear', 'clouds', 'rain']

/**
 * Weather: drifting clouds between the office and the street, rain falling past the windows, and a veil of clouds high
 * above the street, between the camera and the city, that shows only when the view is zoomed out. Clouds are soft
 * puffy sprites, a few shapes drawn once; they always face the camera. The weather changes at random every three
 * minutes: scenery, it says nothing about the company.
 */
export class CityWeather {
  weather: Weather = 'clear'
  private readonly clouds = new THREE.Group()
  private readonly veil = new THREE.Group()
  private readonly rain: THREE.Points
  private readonly center: { x: number; z: number }
  private clock = 0

  constructor(ctx: CityContext) {
    const { root, rnd, cx, cz } = ctx
    this.center = { x: cx, z: cz }
    const shapes = [1, 2, 3, 4].map((s) => cloudTexture(s))
    const cloud = (opacity: number, fog: boolean) =>
      new THREE.Sprite(new THREE.SpriteMaterial({ map: pick(shapes, rnd), transparent: true, opacity, depthWrite: false, ...(fog ? {} : { fog }) }))
    for (let i = 0; i < 9; i++) {
      const c = cloud(0.75, true)
      const size = 12 + rnd() * 12
      c.scale.set(size, size / 2, 1)
      c.position.set(cx - SPAN + rnd() * SPAN * 2, -6 - rnd() * 10, cz - SPAN + rnd() * SPAN * 2)
      this.clouds.add(c)
    }
    root.add(this.clouds)
    for (let i = 0; i < 14; i++) {
      const c = cloud(0, false)
      const size = 10 + rnd() * 12
      c.scale.set(size, size / 2, 1)
      c.position.set(cx - SPAN + rnd() * SPAN * 2, 6 + rnd() * 10, cz - SPAN + rnd() * SPAN * 2)
      c.renderOrder = 5
      this.veil.add(c)
    }
    root.add(this.veil)
    this.rain = this.drops(ctx)
    root.add(this.rain)
  }

  private drops({ rnd, cx, cz }: CityContext): THREE.Points {
    const drops = new Float32Array(DROPS * 3)
    for (let i = 0; i < DROPS; i++) {
      drops.set([cx - SPAN / 2 + rnd() * SPAN, -DEPTH + rnd() * (DEPTH + 12), cz - SPAN / 2 + rnd() * SPAN], i * 3)
    }
    const geometry = new THREE.BufferGeometry()
    geometry.setAttribute('position', new THREE.BufferAttribute(drops, 3))
    return new THREE.Points(geometry, new THREE.PointsMaterial({ color: 0x9fc6e0, size: 2, sizeAttenuation: false, transparent: true, opacity: 0.7 }))
  }

  /** Weather drawn at random. */
  random(): Weather {
    return WEATHERS[Math.floor(Math.random() * 3)]
  }

  set(w: Weather, umbrellas: THREE.Object3D): void {
    this.weather = w
    this.clouds.visible = w !== 'clear'
    this.rain.visible = w === 'rain'
    umbrellas.visible = w === 'rain'
  }

  /**
   * How far the view is zoomed out, 0 (on the office) to 1 (the whole city): the haze thickens and high clouds drift
   * past, keeping clear of the office's own footprint.
   */
  setDistance(k: number, fog: THREE.Fog | null): void {
    for (const c of this.veil.children) {
      const nearTower = Math.abs(c.position.x - this.center.x) < 16 && Math.abs(c.position.z - this.center.z) < 18
      ;(c as THREE.Sprite).material.opacity = nearTower ? 0 : 0.45 * k * k
    }
    if (fog) {
      fog.near = 95 - 25 * k
      fog.far = 170 - 40 * k
    }
  }

  /** Whether the three minutes of a weather are over (then a new one is drawn). */
  due(dt: number): boolean {
    this.clock += dt
    if (this.clock <= 180) return false
    this.clock = 0
    return true
  }

  /** Clouds drift east, the veil faster; rain falls. */
  update(dt: number): void {
    for (const c of [...this.clouds.children, ...this.veil.children]) {
      c.position.x += dt * (c.parent === this.veil ? 2.4 : 1.2)
      if (c.position.x > this.center.x + SPAN) c.position.x -= SPAN * 2
    }
    if (!this.rain.visible) return
    const r = this.rain.geometry.getAttribute('position') as THREE.BufferAttribute
    for (let i = 0; i < DROPS; i++) {
      let y = r.getY(i) - dt * 30
      if (y < -DEPTH) y += DEPTH + 12
      r.setY(i, y)
    }
    r.needsUpdate = true
  }
}
