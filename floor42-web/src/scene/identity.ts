
// Visual identity of staff, shared by the scene and the HUD so a portrait and a figure always match.

const UNIFORMS = [0x2f6fd0, 0x3f9f4a, 0xd0752a, 0x9b4fd0, 0xc94a6a, 0x2aa7a0]

/** The first staff keep the colours they always had; anyone else gets one from their id, the same everywhere. */
const FIRST: Record<string, number> = { chris: 0, dehren: 1, geoffrey: 2 }

/** A stable index for an agent: the same on every screen and after a reload, whoever joins or leaves. */
export function lookOf(agentId: string): number {
  if (agentId in FIRST) return FIRST[agentId]
  let h = 0
  for (const ch of agentId) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return 3 + (h % (UNIFORMS.length * 4))
}

export function uniformOf(agentId: string): number {
  return UNIFORMS[lookOf(agentId) % UNIFORMS.length]
}

export function cssColor(color: number): string {
  return '#' + color.toString(16).padStart(6, '0')
}

const luminance = (color: number): number =>
  [16, 8, 0]
    .map((shift) => ((color >> shift) & 255) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
    .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0)

/** The text colour, dark or white, that reads best (WCAG contrast) on an agent's colour: an initial on a portrait. */
export function inkOn(color: number): string {
  const l = luminance(color)
  const dark = (l + 0.05) / (luminance(0x061016) + 0.05)
  const light = 1.05 / (l + 0.05)
  return dark >= light ? '#061016' : '#ffffff'
}
