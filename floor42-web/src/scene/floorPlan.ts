import type { Pose } from './figures'
import type { StationId } from './mapping'
import type { Point, Rect } from './nav'
import { BOOKCASE } from './props/library'
import { CONFERENCE_SEATS, PING_PONG } from './wing'

// Floor plan in tiles (1 unit = 1 tile). North and west walls are full height; south and east are cut away so the
// rooms stay visible from the isometric camera. Everything an agent can stand at, and everything it must walk around,
// is declared here.

export const FLOOR = { width: 13, depth: 24 }
export const PARTITION_X = 8.5
/** The open space south of the director's floor and the compute room: one desk per agent. */
export const OPEN_SPACE_Z = 10
/** The south wing beyond the open space: the conference room to the west, the kitchen and games to the east. */
export const WING_Z = 15
export const WING_PARTITION_X = 7.6
/** Centre of the conference table, and of the ping-pong table. */
export const CONFERENCE = { x: 3.8, z: 19.6 }
export const PING_PONG_AT = { x: 10.3, z: 21.6 }
/** The glass elevator on the east face, and its landing at the end of the open space: how agents come and go. */
export const ELEVATOR = { x: 13.75, z: 12.15 }
export const LANDING = { x: 12.7, z: 12.15 }

/** Desk centres in the open space, two rows of five; each agent sits on the south side of its own desk. */
export const DESKS: { x: number; z: number }[] = [11.0, 13.3].flatMap((z) =>
  [2.2, 4.6, 7.0, 9.4, 11.8].map((x) => ({ x, z })),
)

export interface Slot {
  x: number
  z: number
  yaw: number
  pose: Pose
}

const NORTH = Math.PI
const SOUTH = 0
const WEST = -Math.PI / 2

/** Several agents may share a station: each gets its own slot. */
export const STATIONS: Record<StationId, Slot[]> = {
  'table-seat': [
    { x: 3.4, z: 5.95, yaw: SOUTH, pose: 'sit' },
    { x: 5.0, z: 5.95, yaw: SOUTH, pose: 'sit' },
    { x: 3.4, z: 8.05, yaw: NORTH, pose: 'sit' },
    { x: 5.0, z: 8.05, yaw: NORTH, pose: 'sit' },
    // Committees seat up to five: the table's ends.
    { x: 2.6, z: 7.0, yaw: -WEST, pose: 'stand' },
    { x: 5.8, z: 7.0, yaw: WEST, pose: 'stand' },
  ],
  // A director's meeting larger than the table's chairs (ADR-0049): standing behind them, then at its ends.
  'meeting-stand': [
    { x: 3.4, z: 5.3, yaw: SOUTH, pose: 'stand' },
    { x: 5.0, z: 5.3, yaw: SOUTH, pose: 'stand' },
    { x: 3.4, z: 8.7, yaw: NORTH, pose: 'stand' },
    { x: 5.0, z: 8.7, yaw: NORTH, pose: 'stand' },
    { x: 2.0, z: 7.0, yaw: -WEST, pose: 'stand' },
    { x: 6.4, z: 7.0, yaw: WEST, pose: 'stand' },
  ],
  'director-front': [
    { x: 4.95, z: 3.25, yaw: WEST, pose: 'stand' },
    { x: 4.95, z: 2.45, yaw: WEST, pose: 'stand' },
    { x: 1.6, z: 3.25, yaw: -WEST, pose: 'stand' },
  ],
  'board-front': [
    { x: 2.4, z: 1.55, yaw: NORTH, pose: 'stand' },
    { x: 3.3, z: 1.6, yaw: NORTH, pose: 'stand' },
    { x: 4.2, z: 1.55, yaw: NORTH, pose: 'stand' },
    { x: 1.5, z: 1.6, yaw: NORTH, pose: 'stand' },
    { x: 5.1, z: 1.6, yaw: NORTH, pose: 'stand' },
  ],
  // Each agent has its own: the scene gives desk i to the i-th agent it meets, for good.
  desk: DESKS.map((d) => ({ x: d.x, z: d.z + 0.75, yaw: NORTH, pose: 'type' as Pose })),
  // The elevator's landing: where arrivals step out and departures step in.
  door: [{ x: LANDING.x, z: LANDING.z, yaw: -WEST, pose: 'stand' }],
  // Idle agents: two at the ping-pong table first, then the sofa (a coffee in hand), then the kitchen, the bean bags
  // and the coffee machines. The first two slots are the ping-pong players: the scene reads them by index.
  lounge: [
    { x: PING_PONG_AT.x - PING_PONG.length / 2 - 0.65, z: PING_PONG_AT.z, yaw: -WEST, pose: 'play' },
    { x: PING_PONG_AT.x + PING_PONG.length / 2 + 0.65, z: PING_PONG_AT.z, yaw: WEST, pose: 'play' },
    { x: 6.15, z: 2.4, yaw: SOUTH, pose: 'lounge' },
    { x: 7.65, z: 2.4, yaw: SOUTH, pose: 'lounge' },
    { x: 6.9, z: 2.4, yaw: SOUTH, pose: 'lounge' },
    { x: 9.45, z: 17.0, yaw: -WEST, pose: 'stand' },
    { x: 10.95, z: 17.0, yaw: WEST, pose: 'stand' },
    { x: 10.2, z: 17.75, yaw: NORTH, pose: 'stand' },
    { x: 8.4, z: 23.2, yaw: NORTH, pose: 'lounge' },
    { x: 9.4, z: 23.3, yaw: NORTH, pose: 'lounge' },
    { x: 11.6, z: 19.7, yaw: -WEST, pose: 'stand' },
  ],
  // Two by two, face to face: an agent thinking over a coffee (even slot) and the idle colleague it talks to (odd
  // slot). At the high table in the kitchen, then by the coffee machine in the lounge.
  chat: [
    { x: 9.45, z: 17.0, yaw: -WEST, pose: 'stand' },
    { x: 10.95, z: 17.0, yaw: WEST, pose: 'stand' },
    { x: 5.9, z: 1.25, yaw: -WEST, pose: 'stand' },
    { x: 7.0, z: 1.25, yaw: WEST, pose: 'stand' },
  ],
  // Committees sit in the conference room: twelve seats, enough for the idea committee.
  'conference-seat': CONFERENCE_SEATS.map((s) => ({
    x: CONFERENCE.x + s.x,
    z: CONFERENCE.z + s.z,
    yaw: s.yaw,
    pose: 'sit' as Pose,
  })),
}

/** The library's bookcase against the west wall of the open space, facing east, clear of the desks and the plants. */
export const BOOKCASE_AT = { x: 0.3, z: 12.2 }

/** The identity server (Keycloak) by the compute room's east wall, its padlocked door facing south, to the camera. */
export const IDENTITY_SERVER_AT = { x: 12.5, z: 2.6 }

/** A footprint centred on (x, z): `w` along x, `d` along z. */
export function around(x: number, z: number, w: number, d: number): Rect {
  return { x0: x - w / 2, z0: z - d / 2, x1: x + w / 2, z1: z + d / 2 }
}

/** An office chair's seat and back, from above. */
const CHAIR = 0.5
/** A potted plant, leaves included: the outer ring arches out about 0.45 from the pot's centre. */
const PLANT = 0.9

/** Potted plants on the floor, wherever they stand: placed by buildOffice, walked around by everyone. */
export const PLANTS: Point[] = [
  { x: 0.6, z: 0.6 },
  { x: 7.8, z: 0.6 },
  { x: 0.6, z: 9.3 },
  { x: 12.5, z: 14.5 },
  { x: 0.6, z: 14.5 },
  { x: 0.6, z: 23.5 },
  { x: 7.1, z: 15.7 },
]
export const STOOLS: Point[] = [
  { x: 9.55, z: 16.5 },
  { x: 10.85, z: 16.5 },
]
export const BEAN_BAGS: (Point & { color: number })[] = [
  { x: 8.4, z: 23.55, color: 0xe0862c },
  { x: 9.4, z: 23.65, color: 0x3fa0c8 },
]

/** Walls and low partitions, with their doorways and passages. */
export const WALLS: Rect[] = [
  // Partition with its doorway between z = 5.5 and z = 7.2
  { x0: 8.4, z0: 0, x1: 8.6, z1: 5.5 },
  { x0: 8.4, z0: 7.2, x1: 8.6, z1: 10 },
  // Open space: low walls with a passage from the director's floor; the compute room is closed on that side.
  { x0: 0, z0: 9.9, x1: 2.4, z1: 10.1 },
  { x0: 5.6, z0: 9.9, x1: 13, z1: 10.1 },
  // South wing: a low wall with two passages from the open space, and a partition between its two rooms.
  { x0: 0, z0: WING_Z - 0.1, x1: 2.6, z1: WING_Z + 0.1 },
  { x0: 4.4, z0: WING_Z - 0.1, x1: 8.8, z1: WING_Z + 0.1 },
  { x0: 10.6, z0: WING_Z - 0.1, x1: 13, z1: WING_Z + 0.1 },
  { x0: WING_PARTITION_X - 0.1, z0: WING_Z, x1: WING_PARTITION_X + 0.1, z1: 24 },
]

/**
 * The footprint of every piece of furniture standing on the floor, chairs, plants and stools included: agents walk
 * around all of them (with a small clearance added by the grid). Chairs, the sofa's seats and the bean bags are also
 * goals, which the grid lets an agent reach. `layout.test.ts` checks every mesh buildOffice stands on the floor
 * against these footprints, so a prop added without one fails the build.
 */
export const FURNITURE: Rect[] = [
  // Director's floor
  { x0: 2.1, z0: 2.15, x1: 4.3, z1: 3.05 }, // desk
  { x0: 3.15, z0: 3.2, x1: 3.65, z1: 3.7 }, // director's chair
  { x0: 3.05, z0: 6.25, x1: 5.35, z1: 7.75 }, // meeting table
  { x0: 3.15, z0: 5.7, x1: 3.65, z1: 6.2 }, // its four chairs
  { x0: 4.75, z0: 5.7, x1: 5.25, z1: 6.2 },
  { x0: 3.15, z0: 7.8, x1: 3.65, z1: 8.3 },
  { x0: 4.75, z0: 7.8, x1: 5.25, z1: 8.3 },
  { x0: 0.17, z0: 4.3, x1: 0.72, z1: 5.6 }, // filing cabinets
  { x0: 0.25, z0: 6.15, x1: 0.9, z1: 7.05 }, // archive reader, its drawer out
  around(BOOKCASE_AT.x, BOOKCASE_AT.z, BOOKCASE.depth, BOOKCASE.width), // the library's bookcase
  { x0: 6.25, z0: 0.25, x1: 6.95, z1: 0.75 }, // coffee machine
  { x0: 5.75, z0: 1.75, x1: 8.05, z1: 2.55 }, // lounge sofa (its seats are goals)
  // Compute room
  { x0: 9.3, z0: 0.2, x1: 12.2, z1: 0.9 }, // mainframe
  { x0: 8.7, z0: 2.7, x1: 9.5, z1: 4.1 }, // terminal console
  { x0: 9.8, z0: 3.15, x1: 10.3, z1: 3.65 }, // operator chair
  { x0: 8.65, z0: 7.75, x1: 9.45, z1: 9.45 }, // server racks
  { x0: 11.05, z0: 8.25, x1: 12.15, z1: 9.4 }, // line printer and its box of paper
  around(IDENTITY_SERVER_AT.x, IDENTITY_SERVER_AT.z, 0.6, 0.7), // the identity server (Keycloak), facing south
  // Open space: each desk, and its chair on the south side.
  ...DESKS.map((d) => around(d.x, d.z, 1.4, 0.7)),
  ...DESKS.map((d) => around(d.x, d.z + 0.75, CHAIR, CHAIR)),
  // Conference room: the table and its twelve chairs.
  around(CONFERENCE.x, CONFERENCE.z, 4.7, 1.4),
  ...CONFERENCE_SEATS.map((s) => around(CONFERENCE.x + s.x, CONFERENCE.z + s.z + Math.sign(s.z) * 0.12, CHAIR, CHAIR)),
  { x0: 0.05, z0: CONFERENCE.z - 1.25, x1: 0.2, z1: CONFERENCE.z + 1.25 }, // wall screen
  // Kitchen and games
  { x0: 12.35, z0: 15.75, x1: 12.95, z1: 18.85 }, // counter
  around(12.6, 15.42, 0.7, 0.7), // fridge
  around(12.5, 19.6, 0.5, 0.7), // coffee machine, turned to face the room
  around(10.2, 17.0, 0.9, 0.9), // bistro table
  ...STOOLS.map((s) => around(s.x, s.z, 0.36, 0.36)),
  around(PING_PONG_AT.x, PING_PONG_AT.z, PING_PONG.length, PING_PONG.width),
  { x0: 7.75, z0: 15.65, x1: 8.65, z1: 16.35 }, // arcade cabinet, its control panel out
  ...BEAN_BAGS.map((b) => around(b.x, b.z, 0.84, 0.84)),
  // Plants, wherever they stand
  ...PLANTS.map((p) => around(p.x, p.z, PLANT, PLANT)),
]

/** Everything agents walk around. */
export const OBSTACLES: Rect[] = [...WALLS, ...FURNITURE]
