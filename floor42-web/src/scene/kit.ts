import * as THREE from 'three';

// Building blocks: every prop is an assembly of boxes and cylinders with flat-shaded, palette-constrained materials.

const materials = new Map<string, THREE.MeshStandardMaterial>();

export function mat(color: number, opts: { emissive?: number; metal?: boolean } = {}): THREE.MeshStandardMaterial {
  const key = `${color}-${opts.emissive ?? 0}-${opts.metal ?? false}`;
  let m = materials.get(key);
  if (!m) {
    m = new THREE.MeshStandardMaterial({
      color,
      flatShading: true,
      roughness: opts.metal ? 0.45 : 0.9,
      metalness: opts.metal ? 0.35 : 0.0,
      emissive: opts.emissive ?? 0x000000,
      emissiveIntensity: opts.emissive ? 1.0 : 0.0,
    });
    materials.set(key, m);
  }
  return m;
}

/** Unlit material: screens, LEDs, holograms. Not affected by lights, not outlined as creases. */
export function glow(color: number): THREE.MeshBasicMaterial {
  return new THREE.MeshBasicMaterial({ color });
}

export function box(
  parent: THREE.Object3D,
  size: [number, number, number],
  pos: [number, number, number],
  material: THREE.Material,
  shadows = true,
): THREE.Mesh {
  const m = new THREE.Mesh(new THREE.BoxGeometry(...size), material);
  // Position is given for the box's bottom-center, which makes stacking furniture natural.
  m.position.set(pos[0], pos[1] + size[1] / 2, pos[2]);
  m.castShadow = shadows;
  m.receiveShadow = true;
  parent.add(m);
  return m;
}

export function cylinder(
  parent: THREE.Object3D,
  radius: number,
  height: number,
  pos: [number, number, number],
  material: THREE.Material,
  segments = 8,
): THREE.Mesh {
  const m = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, height, segments), material);
  m.position.set(pos[0], pos[1] + height / 2, pos[2]);
  m.castShadow = true;
  m.receiveShadow = true;
  parent.add(m);
  return m;
}

/** What the director can point at in the scene. The id refers to domain data (agent id) or to a layout object. */
export interface SceneTarget {
  kind: 'agent' | 'machine' | 'board' | 'desk' | 'table' | 'decor' | 'archive' | 'library' | 'identity' | 'robot' | 'pet';
  id: string;
}

export function markTarget(root: THREE.Object3D, target: SceneTarget): void {
  root.userData.target = target;
}
