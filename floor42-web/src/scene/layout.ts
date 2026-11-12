import * as THREE from 'three'
import { CONFERENCE, OBSTACLES, STATIONS, type Slot } from './floorPlan'
import type { MachineId, StationId } from './mapping'
import type { Rect } from './nav'
import { P } from './palette'
import type { ArchiveReader, ArchiveWall, Bookcase, HoloTable, IdentityServer, LinePrinter, Machine, Mainframe, MissionBoard, TerminalConsole } from './props'
import { computeRoom, directorsFloor, officeLights, openSpaceAndWing, slabAndFloors, wallDetails, walls } from './rooms'

// The office: its floor plan (floorPlan.ts, re-exported here) built room by room (rooms.ts).
export * from './floorPlan'

export interface RoomInfo {
  id: string
  name: string
  center: THREE.Vector3
}

export interface Building {
  scene: THREE.Scene
  machines: Map<MachineId, Machine>
  board: MissionBoard
  /** The archive wall (W6) on the director's floor. */
  archiveWall: ArchiveWall
  /** The library's bookcase: one spine per project. */
  library: Bookcase
  /** The identity server (Keycloak) in the compute room. */
  identity: IdentityServer
  table: HoloTable
  rooms: RoomInfo[]
  stations: Record<StationId, Slot[]>
  obstacles: Rect[]
  /** Decorative blinking lights: ambience only, never driven by events. */
  ambience: THREE.Mesh[]
  desks: THREE.Group[]
  /** The ping-pong ball: shown only while two idle agents stand at the table. */
  ball: THREE.Mesh
  /** Dimmed at night, when the plan's quota is spent. */
  lights: { hemi: THREE.HemisphereLight; key: THREE.DirectionalLight; points: THREE.PointLight[] }
}

const ROOMS: { id: string; name: string; at: [number, number] }[] = [
  { id: 'room-director', name: "Director's floor", at: [4, 5] },
  { id: 'room-compute', name: 'Compute room', at: [10.8, 4] },
  { id: 'room-open-space', name: 'Open space', at: [6.5, 12.4] },
  { id: 'room-conference', name: 'Conference room', at: [CONFERENCE.x, CONFERENCE.z] },
  { id: 'room-kitchen', name: 'Kitchen and games', at: [10.3, 19.5] },
]

/** The machines the journal's executions drive: each reacts to its own. */
function machinesOf(terminal: TerminalConsole, mainframe: Mainframe, archive: ArchiveReader, printer: LinePrinter): Map<MachineId, Machine> {
  return new Map<MachineId, Machine>([
    ['terminal-1', terminal],
    ['mainframe-1', mainframe],
    ['archive-1', archive],
    ['printer-1', printer],
  ])
}

export function buildOffice(): Building {
  const scene = new THREE.Scene()
  scene.background = new THREE.Color(P.void)
  slabAndFloors(scene)
  walls(scene)
  const { board, archiveWall, table, archive, library } = directorsFloor(scene)
  const { mainframe, terminal, printer, identity } = computeRoom(scene)
  const { desks, ball } = openSpaceAndWing(scene)
  wallDetails(scene)
  const lights = officeLights(scene)
  const ambience: THREE.Mesh[] = []
  scene.traverse((o) => {
    if (o.userData.blink) ambience.push(o as THREE.Mesh)
  })
  return {
    scene,
    machines: machinesOf(terminal, mainframe, archive, printer),
    board,
    archiveWall,
    library,
    identity,
    table,
    rooms: ROOMS.map((r) => ({ id: r.id, name: r.name, center: new THREE.Vector3(r.at[0], 0, r.at[1]) })),
    desks,
    ball,
    lights,
    stations: STATIONS,
    obstacles: OBSTACLES,
    ambience,
  }
}
