import type { Cover } from '../scene/cover'

const hex = (c: number): string => `#${(c & 0xffffff).toString(16).padStart(6, '0')}`

/**
 * A pixel-art book cover as an SVG string, from a {@link Cover}: cloth, a foil border, a band, the mirrored glyph of
 * the project. Only numbers go into it (never the project's name), so it is safe to inject as markup.
 * The grid is 24 by 32 cells.
 */
export function coverSvg(cover: Cover): string {
  const cells: string[] = []
  const cell = (x: number, y: number, w: number, h: number, color: number) =>
    cells.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${hex(color)}"/>`)
  cell(0, 0, 24, 32, cover.base)
  // The spine's crease on the left, the foil border.
  cell(0, 0, 2, 32, cover.spine)
  cell(3, 2, 19, 1, cover.accent)
  cell(3, 29, 19, 1, cover.accent)
  cell(3, 2, 1, 28, cover.accent)
  cell(21, 2, 1, 28, cover.accent)
  // The band across the cover: four positions.
  const bandY = [6, 9, 21, 24][cover.band & 3]
  cell(5, bandY, 15, 2, cover.ink)
  // The glyph: three columns of five cells, mirrored to the right, each cell 2 by 2.
  for (let col = 0; col < 3; col++) {
    for (let row = 0; row < 5; row++) {
      if (!((cover.glyph >> (col * 5 + row)) & 1)) continue
      const y = 13 + row * 2
      cell(7 + col * 2, y, 2, 2, cover.accent)
      if (col < 2) cell(15 - col * 2, y, 2, 2, cover.accent)
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 32" shape-rendering="crispEdges" aria-hidden="true">${cells.join('')}</svg>`
}
