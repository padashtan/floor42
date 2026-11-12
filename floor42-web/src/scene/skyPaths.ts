// Pure rules for the aircraft passing in the sky behind the office: which craft flies, along which line, how fast,
// when the next one comes, and when its lights blink. Scenery only, like the city: nothing here says anything about the
// company. No three.js here, so the rules are tested on their own (skyPaths.test.ts).

/** What flies past: airliners, helicopters, an airship with its light board, small drones. */
export type Craft = 'plane' | 'helicopter' | 'blimp' | 'drone'

export const CRAFTS: Craft[] = ['plane', 'helicopter', 'blimp', 'drone']

export interface Vec3 {
  x: number
  y: number
  z: number
}

/** One crossing of the sky: a straight line flown at constant speed, from off screen to off screen. */
export interface Flight {
  id: number
  craft: Craft
  from: Vec3
  to: Vec3
  /** Units per second along the line. */
  speed: number
  /** Scene-clock second the craft passed `from`. */
  start: number
  /** Seconds from `from` to `to`. */
  duration: number
  /** A per-flight phase, so two craft do not blink or bob in step. */
  phase: number
}

interface Spec {
  /** How likely this craft is picked, against the others. */
  weight: number
  /** At most this many in the sky at once. */
  max: number
  /** Units per second. */
  speed: [number, number]
  /** Height above the office floor (y = 0), at the middle of the crossing. */
  altitude: [number, number]
  /** How far behind the tower, along the camera's line of sight, at the middle of the crossing. */
  behind: [number, number]
  /** Largest heading change from a straight screen crossing, in radians. */
  skew: number
  /** Largest climb (or descent) over the whole crossing, in units: airliners climb out or come down to land. */
  climb: number
}

/**
 * The camera is orthographic: distance does not shrink a craft, it only blurs it (the pipeline's depth of field starts
 * about 62 units from the camera). So the sky's lanes lie just behind the tower and high up: on screen they pass above
 * the far walls, in the haze where the city fades out, still sharp. Speeds and heights are scaled to the scene (1 unit
 * is about a metre of office, but the city is a model): what matters is the order, an airliner fastest and highest, a
 * helicopter lower and slower, a drone slow and low, the airship barely moving.
 */
export const SPECS: Record<Craft, Spec> = {
  plane: { weight: 4, max: 2, speed: [5, 7], altitude: [9, 13], behind: [18, 24], skew: 0.18, climb: 3 },
  helicopter: { weight: 3, max: 1, speed: [3, 4.2], altitude: [6, 9], behind: [18, 22], skew: 0.25, climb: 1 },
  blimp: { weight: 1, max: 1, speed: [0.8, 1.2], altitude: [8, 11], behind: [18, 22], skew: 0.08, climb: 0 },
  drone: { weight: 2, max: 2, speed: [1.6, 2.4], altitude: [4, 6], behind: [18, 20], skew: 0.3, climb: 0.5 },
}

/** Nothing flies below this height: every neighbour, antennas and spires included, stays under y = -2. */
export const SKY_FLOOR = 3
/** Nothing flies nearer than this to the tower, along the camera's line of sight: the office's far corner is ~13.1. */
export const BEHIND_MIN = 18
/** Half the length of a crossing along the screen: the craft enters and leaves out of view, even zoomed right out. */
export const HALF_SPAN = 85
/** At most this many craft at once, whatever the specs allow. */
export const MAX_CRAFT = 4

/** Ground direction the camera looks along (away from it, behind the office), and its screen-right direction. */
const BEHIND = { x: -Math.SQRT1_2, z: -Math.SQRT1_2 }
const RIGHT = { x: Math.SQRT1_2, z: -Math.SQRT1_2 }

const between = (r: () => number, [a, b]: [number, number]) => a + (b - a) * r()

/** Picks a craft by weight among those still allowed. Null when none is. */
export function pickCraft(allowed: Craft[], r: () => number): Craft | null {
  const total = allowed.reduce((s, c) => s + SPECS[c].weight, 0)
  if (total <= 0) return null
  let u = r() * total
  for (const c of allowed) {
    u -= SPECS[c].weight
    if (u < 0) return c
  }
  return allowed[allowed.length - 1]
}

/** How far `p` lies behind the tower centre `c`, along the camera's line of sight (ground plane). */
export function behindOf(p: { x: number; z: number }, c: { x: number; z: number }): number {
  return (p.x - c.x) * BEHIND.x + (p.z - c.z) * BEHIND.z
}

/**
 * A crossing for `craft` behind the tower centred on `center`: a straight line across the screen, slightly skewed,
 * entering on either side. Clear of the city and the office by construction: the skew is bounded so the line never
 * comes nearer than BEHIND_MIN, and the climb so it never sinks under SKY_FLOOR. `way` forces the screen direction
 * (1 to the right, -1 to the left); otherwise it is drawn.
 */
export function planFlight(
  craft: Craft,
  center: { x: number; z: number },
  r: () => number,
  start: number,
  id: number,
  way?: 1 | -1,
): Flight {
  const s = SPECS[craft]
  const behind = between(r, s.behind)
  // The skew moves the line nearer at one end: bounded so that end stays BEHIND_MIN away.
  const room = Math.max(0, behind - BEHIND_MIN) / HALF_SPAN
  const skew = Math.min(s.skew, Math.atan(room)) * (r() * 2 - 1)
  const altitude = between(r, s.altitude)
  const climb = Math.min(s.climb, 2 * (altitude - SKY_FLOOR)) * (r() * 2 - 1)
  const drawn = r() < 0.5 ? 1 : -1
  const at = crossing(center, behind, skew, altitude, climb, way ?? drawn)
  const from = at(-1)
  const to = at(1)
  const speed = between(r, s.speed)
  const length = Math.hypot(to.x - from.x, to.y - from.y, to.z - from.z)
  return { id, craft, from, to, speed, start, duration: length / speed, phase: r() * 10 }
}

/** The point at `u` (-1 to 1, entering to leaving) of a line `behind` the tower, turned by `skew`, climbing by `climb`. */
function crossing(center: { x: number; z: number }, behind: number, skew: number, altitude: number, climb: number, dir: 1 | -1) {
  return (u: number): Vec3 => {
    const along = u * HALF_SPAN * dir
    const back = behind + along * Math.tan(skew)
    return {
      x: center.x + BEHIND.x * back + RIGHT.x * along,
      y: altitude + (u * climb) / 2,
      z: center.z + BEHIND.z * back + RIGHT.z * along,
    }
  }
}

/** Which way a flight crosses the screen: 1 to the right, -1 to the left. */
export function wayOf(f: Flight, center: { x: number; z: number }): 1 | -1 {
  return (f.to.x - center.x) * RIGHT.x + (f.to.z - center.z) * RIGHT.z >= 0 ? 1 : -1
}

/** Where a flight is at scene time `t`, and its unit heading. `u` runs 0 to 1 over the crossing. */
export function positionAt(f: Flight, t: number): { p: Vec3; heading: Vec3; u: number } {
  const u = Math.min(1, Math.max(0, (t - f.start) / f.duration))
  const d = { x: f.to.x - f.from.x, y: f.to.y - f.from.y, z: f.to.z - f.from.z }
  const n = Math.hypot(d.x, d.y, d.z) || 1
  return {
    p: { x: f.from.x + d.x * u, y: f.from.y + d.y * u, z: f.from.z + d.z * u },
    heading: { x: d.x / n, y: d.y / n, z: d.z / n },
    u,
  }
}

/** True when every point of the flight is above SKY_FLOOR and at least BEHIND_MIN behind the tower. */
export function clearOfCity(f: Flight, center: { x: number; z: number }): boolean {
  // A straight line: its ends are its extremes.
  return [f.from, f.to].every((p) => p.y >= SKY_FLOOR - 1e-9 && behindOf(p, center) >= BEHIND_MIN - 1e-9)
}

/** The sky's traffic: the flights in the air and when the next one takes off. */
export interface Traffic {
  flights: Flight[]
  /** Scene-clock second of the next take-off. */
  next: number
  nextId: number
}

export interface TrafficRules {
  /** At most this many at once (MAX_CRAFT at most). */
  max: number
  /** The kinds the era allows: no drones before the 2020s. */
  crafts: Craft[]
  /** Seconds between two take-offs. */
  gap: [number, number]
}

export const DEFAULT_GAP: [number, number] = [8, 22]

/** The first take-off comes soon after the page opens, so the sky is not empty for long. */
export function startTraffic(now: number, r: () => number): Traffic {
  return { flights: [], next: now + 2 + r() * 4, nextId: 1 }
}

/**
 * Advances the traffic to scene time `now`: finished flights land (are dropped) and, when the next take-off is due and
 * the rules leave room, a new flight starts. One take-off per call at most, so a long pause never releases a swarm.
 */
export function stepTraffic(
  traffic: Traffic,
  now: number,
  rules: TrafficRules,
  center: { x: number; z: number },
  r: () => number,
): Traffic {
  const flights = traffic.flights.filter((f) => now < f.start + f.duration)
  if (now < traffic.next) return flights.length === traffic.flights.length ? traffic : { ...traffic, flights }
  const max = Math.min(MAX_CRAFT, rules.max)
  const next = now + between(r, rules.gap)
  if (flights.length >= max) return { ...traffic, flights, next }
  const allowed = rules.crafts.filter((c) => flights.filter((f) => f.craft === c).length < SPECS[c].max)
  const craft = pickCraft(allowed, r)
  if (!craft) return { ...traffic, flights, next }
  // A second craft of the same kind flies the other way: two airliners abreast would overlap on screen for half a minute.
  const twin = flights.find((f) => f.craft === craft)
  const flight = planFlight(craft, center, r, now, traffic.nextId, twin ? (-wayOf(twin, center) as 1 | -1) : undefined)
  return { flights: [...flights, flight], next, nextId: traffic.nextId + 1 }
}

/** The kinds of craft a decade shows: drones belong to the 2020s. */
export function craftsOf(era: string): Craft[] {
  return era === '2020s' ? CRAFTS : CRAFTS.filter((c) => c !== 'drone')
}

/** How many craft the frame rate allows: one when the scene already struggles, none when it is not measured yet. */
export function budgetFor(fps: number): number {
  if (fps <= 0) return MAX_CRAFT
  return fps < 30 ? 1 : MAX_CRAFT
}

/** Night for the sky's lights: the same hours as the city's lit windows. */
export function isDark(hour: number): boolean {
  return hour < 7 || hour >= 19.5
}

/** A navigation light on a craft. */
export type LightKind = 'port' | 'starboard' | 'tail' | 'beacon' | 'strobe'

/**
 * Whether a light is on at time `t`. Steady position lights (red port, green starboard, white tail) burn all night; the
 * red beacon flashes about once a second; white strobes double-flash every 1.2 s, as on airliners.
 */
export function lightOn(kind: LightKind, t: number, phase: number): boolean {
  if (kind === 'beacon') return ((t + phase) % 1.1) < 0.12
  if (kind === 'strobe') {
    const k = (t + phase * 0.7) % 1.2
    return k < 0.05 || (k > 0.15 && k < 0.2)
  }
  return true
}

/** The seeded generator the city uses, for tests and for a sky that is the same on every screen if wanted. */
export function seeded(seed: number): () => number {
  let s = seed
  return () => {
    s = (s * 16807) % 2147483647
    return (s - 1) / 2147483646
  }
}
