import type { ThemeId } from './palette'

// The cover of a project's book, from its name and path alone: no image, no network, the same every time. The library
// panel draws it (ui/coverArt.ts) and the bookcase in the office paints the spine with the same colours. Pure: no
// Vue, no three.js.

/** What a cover is made of: colours as 0xRRGGBB, and the bits of its motif (a 5 by 5 glyph, mirrored). */
export interface Cover {
  base: number
  accent: number
  ink: number
  /** The book's spine on the shelf. */
  spine: number
  /** 15 bits: three columns of five cells, the right half mirrors the left. */
  glyph: number
  /** 0 to 3: the band across the cover. */
  band: number
}

/** Cover cloth of each decade: the eighties' primary, the nineties' dark, the two-thousands' glossy, today's flat. */
const CLOTH: Record<ThemeId, readonly number[]> = {
  '1980s': [0x1b4d8f, 0xa3262a, 0x1f6b3a, 0x7a4b1f, 0x4a2a7a, 0x20201e],
  '1990s': [0x2c3e50, 0x7b2d26, 0x2d5a45, 0x8a5a2b, 0x3b2f5a, 0x4a4a4a],
  '2000s': [0x2f6fb5, 0xc0392b, 0x2e9b6a, 0xe08a2e, 0x7a4fb0, 0x16a1a8],
  '2020s': [0x3d5af1, 0xe8505b, 0x1fa97a, 0xf3a712, 0x8e5cf7, 0x14b8c4],
}
/** Stamping colours: light enough on any cloth. */
const FOIL: Record<ThemeId, readonly number[]> = {
  '1980s': [0xffd24a, 0xf2e8c9, 0x8be0a4],
  '1990s': [0xd9b25f, 0xe8e2cf, 0x9fd1c0],
  '2000s': [0xffe066, 0xf5f7fa, 0xa8e6cf],
  '2020s': [0xfff1a8, 0xffffff, 0xb6f0d8],
}

/** FNV-1a, 32 bits: stable across runs and engines. */
export function hash(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h >>> 0
}

/** The cover of the project at `key` (its path), in the colours of `era`. */
export function coverOf(key: string, era: ThemeId): Cover {
  const h = hash(key)
  const cloth = CLOTH[era]
  const foil = FOIL[era]
  const base = cloth[h % cloth.length]
  const accent = foil[(h >>> 5) % foil.length]
  const inkAt = (h >>> 9) % foil.length
  const ink = foil[inkAt] === accent ? foil[(inkAt + 1) % foil.length] : foil[inkAt]
  // The spine is the cloth, a little darker, so the shelf reads as a row of books and not of covers.
  const spine = ((((base >> 16) & 255) * 0.85) << 16) | ((((base >> 8) & 255) * 0.85) << 8) | ((base & 255) * 0.85)
  return { base, accent, ink, spine: spine & 0xffffff, glyph: (h >>> 12) & 0x7fff || 0x2a95, band: (h >>> 27) & 3 }
}
