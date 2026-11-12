import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import {
  BEAN_BAGS,
  CONFERENCE,
  DESKS,
  FLOOR,
  OPEN_SPACE_Z,
  PARTITION_X,
  PING_PONG_AT,
  BOOKCASE_AT,
  IDENTITY_SERVER_AT,
  PLANTS,
  STOOLS,
  WING_PARTITION_X,
  WING_Z,
} from './floorPlan'
import { box, markTarget, mat } from './kit'
import { P } from './palette'
import {
  ArchiveReader,
  ArchiveWall,
  Bookcase,
  cableTray,
  coffeeMachine,
  directorDesk,
  filingCabinet,
  HoloTable,
  IdentityServer,
  lightStrip,
  LinePrinter,
  Mainframe,
  MissionBoard,
  officeChair,
  pipeRun,
  plant,
  serverRack,
  sofa,
  TerminalConsole,
  workDesk,
} from './props'
import { carpet, deckPlate, hazardStripes, raisedTile, wallPanel } from './textures'
import { arcadeCabinet, beanBag, bistroTable, conferenceTable, fridge, kitchenCounter, pingPongBall, pingPongTable, stool, wallScreen } from './wing'

// The office built room by room, on the floor plan of floorPlan.ts. Each builder adds to the scene in a fixed order.

function floor(scene: THREE.Scene, x0: number, z0: number, w: number, d: number, texture: THREE.Texture): void {
  const t = texture.clone()
  t.repeat.set(w, d)
  t.needsUpdate = true
  const m = new THREE.Mesh(new THREE.PlaneGeometry(w, d), new THREE.MeshStandardMaterial({ map: t, roughness: 0.95 }))
  m.rotation.x = -Math.PI / 2
  m.position.set(x0 + w / 2, 0.001, z0 + d / 2)
  m.receiveShadow = true
  scene.add(m)
}

function wall(
  scene: THREE.Scene,
  from: [number, number],
  to: [number, number],
  height: number,
  textured: boolean,
  color: number = P.wallBase,
  trim: number = P.wallTrim,
): void {
  const len = Math.hypot(to[0] - from[0], to[1] - from[1])
  const alongX = from[1] === to[1]
  let material: THREE.Material
  if (textured) {
    const t = wallPanel()
    t.repeat.set(len, height / 2)
    material = new THREE.MeshStandardMaterial({ map: t, roughness: 0.9 })
  } else {
    material = mat(color)
  }
  const size: [number, number, number] = alongX ? [len, height, 0.2] : [0.2, height, len]
  const w = box(scene, size, [(from[0] + to[0]) / 2, 0, (from[1] + to[1]) / 2], material)
  w.castShadow = textured
  box(scene, alongX ? [len + 0.2, 0.08, 0.3] : [0.3, 0.08, len + 0.2], [(from[0] + to[0]) / 2, height, (from[1] + to[1]) / 2], mat(trim))
}

/**
 * Glass partitions standing on low walls, each from its trim up to `top`: one pane, and its frame (a steel post at each
 * end, a steel rail on top) as one mesh: every mesh more costs each change of decade (D18's gate in scene.e2e.ts).
 * Neither takes a click (what lies behind does), and agents walk where the low walls already send them: the floor plan
 * does not change.
 */
function glassPartitions(scene: THREE.Scene, runs: { from: [number, number]; to: [number, number]; base: number }[], top = 2.4): void {
  const glass = new THREE.MeshStandardMaterial({ color: 0xbfe6f5, transparent: true, opacity: 0.14, roughness: 0.05, depthWrite: false })
  const piece = (size: [number, number, number], x: number, y: number, z: number) =>
    new THREE.BoxGeometry(...size).translate(x, y + size[1] / 2, z)
  for (const { from, to, base } of runs) {
    const len = Math.hypot(to[0] - from[0], to[1] - from[1])
    const alongX = from[1] === to[1]
    const [mx, mz] = [(from[0] + to[0]) / 2, (from[1] + to[1]) / 2]
    const frame = [piece(alongX ? [len, 0.05, 0.07] : [0.07, 0.05, len], mx, top, mz)]
    for (const [x, z] of [from, to]) frame.push(piece([0.05, top - base, 0.05], x, base, z))
    const pane = new THREE.Mesh(piece(alongX ? [len, top - base, 0.03] : [0.03, top - base, len], mx, base, mz), glass)
    pane.userData.noOutline = true
    const rail = new THREE.Mesh(mergeGeometries(frame), mat(P.steel, { metal: true }))
    for (const g of frame) g.dispose()
    for (const m of [pane, rail]) {
      m.raycast = () => {}
      scene.add(m)
    }
  }
}

export function place<T extends THREE.Object3D>(scene: THREE.Scene, o: T, x: number, z: number, yaw = 0): T {
  o.position.set(x, 0, z)
  o.rotation.y = yaw
  scene.add(o)
  return o
}

/** The slab under the floor (its dark sides read as the building section, as in a cutaway game view), floors, the rug. */
export function slabAndFloors(scene: THREE.Scene): void {
  const { width: W, depth: D } = FLOOR
  box(scene, [W + 0.4, 0.5, D + 0.4], [W / 2, -0.5, D / 2], mat(P.wallBase))
  box(scene, [W + 0.4, 0.06, D + 0.4], [W / 2, -0.06, D / 2], mat(P.orange), false)
  floor(scene, 0, 0, PARTITION_X, OPEN_SPACE_Z, deckPlate(P.floorMetal, P.floorMetalDark, P.floorLine))
  floor(scene, PARTITION_X, 0, W - PARTITION_X, OPEN_SPACE_Z, raisedTile(P.raisedTile, P.raisedTileDark))
  floor(scene, 0, OPEN_SPACE_Z, W, WING_Z - OPEN_SPACE_Z, carpet(P.openRug, P.openRugDark))
  floor(scene, 0, WING_Z, WING_PARTITION_X, D - WING_Z, carpet(P.confCarpet, P.confCarpetDark))
  floor(scene, WING_PARTITION_X, WING_Z, W - WING_PARTITION_X, D - WING_Z, raisedTile(P.kitchenTile, P.kitchenTileDark))
  const rug = carpet(P.carpet, P.carpetDark)
  rug.repeat.set(5, 3.6)
  const rugMesh = new THREE.Mesh(new THREE.PlaneGeometry(5, 3.6), new THREE.MeshStandardMaterial({ map: rug }))
  rugMesh.rotation.x = -Math.PI / 2
  rugMesh.position.set(3.2, 0.004, 3.2)
  rugMesh.receiveShadow = true
  scene.add(rugMesh)
}

/** Full-height north and west walls, low south and east ones, the partitions and the hazard stripes at the doorway. */
export function walls(scene: THREE.Scene): void {
  const { width: W, depth: D } = FLOOR
  wall(scene, [0, 0], [W, 0], 2.8, true)
  wall(scene, [0, 0], [0, D], 2.8, true)
  wall(scene, [0, D], [W, D], 0.3, false)
  wall(scene, [W, 0], [W, D], 0.3, false)
  wall(scene, [PARTITION_X, 0], [PARTITION_X, 5.5], 0.9, false, P.steel, P.orange)
  wall(scene, [PARTITION_X, 7.2], [PARTITION_X, OPEN_SPACE_Z], 0.9, false, P.steel, P.orange)
  wall(scene, [0, OPEN_SPACE_Z], [2.4, OPEN_SPACE_Z], 0.6, false, P.steel, P.orange)
  wall(scene, [5.6, OPEN_SPACE_Z], [W, OPEN_SPACE_Z], 0.6, false, P.steel, P.orange)
  wall(scene, [0, WING_Z], [2.6, WING_Z], 0.6, false, P.steel, P.orange)
  wall(scene, [4.4, WING_Z], [8.8, WING_Z], 0.6, false, P.steel, P.orange)
  wall(scene, [10.6, WING_Z], [W, WING_Z], 0.6, false, P.steel, P.orange)
  wall(scene, [WING_PARTITION_X, WING_Z], [WING_PARTITION_X, D], 0.9, false, P.steel, P.orange)
  // The director's floor behind glass, on its low partitions; the doorways stay open.
  glassPartitions(scene, [
    { from: [PARTITION_X, 0], to: [PARTITION_X, 5.5], base: 0.98 },
    { from: [PARTITION_X, 7.2], to: [PARTITION_X, OPEN_SPACE_Z], base: 0.98 },
    { from: [0, OPEN_SPACE_Z], to: [2.4, OPEN_SPACE_Z], base: 0.68 },
    { from: [5.6, OPEN_SPACE_Z], to: [PARTITION_X, OPEN_SPACE_Z], base: 0.68 },
  ])
  const hazard = hazardStripes()
  hazard.repeat.set(1.7, 0.3)
  box(scene, [0.5, 0.02, 1.7], [PARTITION_X, 0, 6.35], new THREE.MeshStandardMaterial({ map: hazard }), false)
}

/** The director's floor: the desk, the mission board, the archive wall and reader, the holo table, the lounge. */
export function directorsFloor(scene: THREE.Scene): { board: MissionBoard; archiveWall: ArchiveWall; library: Bookcase; table: HoloTable; archive: ArchiveReader } {
  const desk = place(scene, directorDesk(), 3.2, 2.6)
  markTarget(desk, { kind: 'desk', id: 'director-desk' })
  const board = new MissionBoard()
  board.root.position.set(3.2, 1.0, 0.12)
  scene.add(board.root)
  const archiveWall = new ArchiveWall()
  archiveWall.root.position.set(0.12, 1.5, 2.6)
  archiveWall.root.rotation.y = Math.PI / 2
  scene.add(archiveWall.root)
  const table = new HoloTable()
  place(scene, table.root, 4.2, 7.0)
  for (const z of [4.6, 5.3]) place(scene, filingCabinet(), 0.45, z)
  const library = new Bookcase()
  place(scene, library.root, BOOKCASE_AT.x, BOOKCASE_AT.z, Math.PI / 2)
  place(scene, coffeeMachine(), 6.6, 0.5)
  place(scene, sofa(), 6.9, 2.15)
  const archive = new ArchiveReader()
  place(scene, archive.root, 0.55, 6.6, Math.PI / 2)
  return { board, archiveWall, table, archive, library }
}

/** The compute room: the mainframe, the terminal and its operator's chair, the line printer, the server racks, Keycloak. */
export function computeRoom(scene: THREE.Scene): { mainframe: Mainframe; terminal: TerminalConsole; printer: LinePrinter; identity: IdentityServer } {
  const mainframe = new Mainframe()
  place(scene, mainframe.root, 10.75, 0.55)
  const terminal = new TerminalConsole()
  place(scene, terminal.root, PARTITION_X + 0.6, 3.4)
  place(scene, officeChair(P.steel), PARTITION_X + 1.55, 3.4, -Math.PI / 2)
  const printer = new LinePrinter()
  place(scene, printer.root, 11.6, 8.6)
  for (const z of [8.1, 9.0]) place(scene, serverRack(), 9.05, z, Math.PI / 2)
  place(scene, cableTray(3.4), 10.6, 5.4)
  const identity = new IdentityServer()
  place(scene, identity.root, IDENTITY_SERVER_AT.x, IDENTITY_SERVER_AT.z)
  return { mainframe, terminal, printer, identity }
}

/** The open space's desks, then the south wing: the conference room, the kitchen and the games corner, the plants. */
export function openSpaceAndWing(scene: THREE.Scene): { desks: THREE.Group[]; ball: THREE.Mesh } {
  const desks = DESKS.map((d) => place(scene, workDesk(), d.x, d.z))
  // The conference table calls a meeting too (ADR-0047): the same target as the meeting table.
  markTarget(place(scene, conferenceTable(), CONFERENCE.x, CONFERENCE.z), { kind: 'table', id: 'conference-1' })
  place(scene, wallScreen(), 0.12, CONFERENCE.z)
  place(scene, kitchenCounter(3.1), 12.65, 17.3)
  place(scene, fridge(), 12.6, 15.42)
  place(scene, coffeeMachine(), 12.5, 19.6, -Math.PI / 2)
  place(scene, bistroTable(), 10.2, 17.0)
  for (const s of STOOLS) place(scene, stool(), s.x, s.z)
  place(scene, pingPongTable(), PING_PONG_AT.x, PING_PONG_AT.z)
  const ball = pingPongBall()
  scene.add(ball)
  place(scene, arcadeCabinet(), 8.1, 16.0, Math.PI / 2)
  for (const b of BEAN_BAGS) place(scene, beanBag(b.color), b.x, b.z)
  for (const p of PLANTS) place(scene, plant(), p.x, p.z)
  return { desks, ball }
}

/** Light strips along the north and west walls, and the pipes under the ceiling. */
export function wallDetails(scene: THREE.Scene): void {
  const { width: W, depth: D } = FLOOR
  for (const x of [1.4, 5.8, 9.4, 12.2]) {
    const lamp = lightStrip(0.7)
    lamp.position.set(x, 2.05, 0.14)
    scene.add(lamp)
  }
  for (const z of [1.6, 7.6, 11.0, 14.0, 18.0, 21.5]) {
    const lamp = lightStrip(0.7)
    lamp.rotation.y = Math.PI / 2
    lamp.position.set(0.14, 2.05, z)
    scene.add(lamp)
  }
  const pipes = pipeRun(D - 0.4, P.steel)
  pipes.rotation.y = Math.PI / 2
  pipes.position.set(0.25, 2.45, D / 2)
  pipes.visible = false
  scene.add(pipes)
  const pipes2 = pipeRun(W - 0.4, P.orange)
  pipes2.position.set(W / 2, 2.55, 0.25)
  scene.add(pipes2)
}

/** Light: cool ambient, a warm key casting pixel shadows, and local glows from equipment. */
export function officeLights(scene: THREE.Scene): { hemi: THREE.HemisphereLight; key: THREE.DirectionalLight; points: THREE.PointLight[] } {
  const hemi = new THREE.HemisphereLight(P.sky, P.ground, 1.9)
  scene.add(hemi)
  const key = keyLight()
  scene.add(key, key.target)
  const points: THREE.PointLight[] = []
  for (const [color, intensity, distance, x, y, z] of [
    [P.cyan, 4, 5, 3.2, 1.8, 1.2],
    [0xffc27a, 3, 3.5, 4.0, 1.4, 2.6],
    [0xbfe9ff, 5, 7, 10.8, 2.6, 4.5],
    [0xfff1d6, 4, 7, 4.0, 2.6, 12.3],
    [0xfff1d6, 4, 7, 9.5, 2.6, 12.3],
    [0xfff1d6, 4, 7, 3.8, 2.6, 19.6],
    [0xffe2b8, 4, 7, 10.3, 2.6, 19.5],
  ] as const) {
    const light = new THREE.PointLight(color, intensity, distance, 1.4)
    light.position.set(x, y, z)
    scene.add(light)
    points.push(light)
  }
  return { hemi, key, points }
}

/** The warm key light over the floor's middle, casting the shadows. */
function keyLight(): THREE.DirectionalLight {
  const { width: W, depth: D } = FLOOR
  const key = new THREE.DirectionalLight(P.sun, 2.4)
  key.position.set(W / 2 - 6, 14, D / 2 + 3)
  key.target.position.set(W / 2, 0, D / 2)
  key.castShadow = true
  key.shadow.mapSize.set(1024, 1024)
  Object.assign(key.shadow.camera, { left: -16, right: 16, top: 16, bottom: -16 })
  key.shadow.bias = -0.0015
  // Soft-edged shadows (PCF filtering over a wider kernel): cosier than hard ones, still pixel-snapped by the pipeline.
  key.shadow.radius = 3
  return key
}
