import type * as THREE from 'three'
import type { Figure } from './figures'

// What the scene writes on its canvas for the browser tests: data only, never read back by the page.

/** Where the named objects are on screen: "robot:x,y;cat:x,y;...". */
export function screenMarks(
  canvas: HTMLCanvasElement,
  camera: THREE.Camera,
  scratch: THREE.Vector3,
  named: Record<string, THREE.Object3D>,
): string {
  const rect = canvas.getBoundingClientRect()
  return Object.entries(named)
    .map(([name, o]) => {
      o.getWorldPosition(scratch)
      scratch.y += 0.22
      const p = scratch.project(camera)
      return `${name}:${Math.round(rect.left + ((p.x + 1) / 2) * rect.width)},${Math.round(rect.top + ((1 - p.y) / 2) * rect.height)}`
    })
    .join(';')
}

/** Each agent: "id:1|0 (in the office or not):x,z" on the floor; and who speaks now, space separated. */
export function officeMarks(figures: Iterable<Figure>, now: number): { office: string; speaking: string } {
  const office: string[] = []
  const speaking: string[] = []
  for (const f of figures) {
    office.push(`${f.id}:${f.root.visible ? 1 : 0}:${f.root.position.x.toFixed(1)},${f.root.position.z.toFixed(1)}`)
    if (f.root.visible && f.speaks(now)) speaking.push(f.id)
  }
  return { office: office.join(';'), speaking: speaking.join(' ') }
}
