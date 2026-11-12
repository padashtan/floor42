import { inflateSync } from 'node:zlib'

/** A decoded picture: RGBA bytes, row after row. */
export interface Picture {
  width: number
  height: number
  rgba: Uint8Array
}

const paeth = (a: number, b: number, c: number): number => {
  const p = a + b - c
  const pa = Math.abs(p - a)
  const pb = Math.abs(p - b)
  const pc = Math.abs(p - c)
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c
}

/** Undoes the filter of one row in place: `prev` is the row above, already decoded (zeros for the first). */
function unfilter(type: number, row: Uint8Array, prev: Uint8Array, bpp: number): void {
  for (let i = 0; i < row.length; i++) {
    const left = i >= bpp ? row[i - bpp] : 0
    const up = prev[i]
    const corner = i >= bpp ? prev[i - bpp] : 0
    const add = type === 1 ? left : type === 2 ? up : type === 3 ? (left + up) >> 1 : type === 4 ? paeth(left, up, corner) : 0
    row[i] = (row[i] + add) & 0xff
  }
}

/**
 * Decodes the PNG files the browser writes for a screenshot: 8 bits per channel, RGB or RGBA, not interlaced. The
 * page's Content-Security-Policy refuses data URLs, so the comparison happens here rather than in the page.
 */
export function decodePng(file: Buffer): Picture {
  let pos = 8
  let width = 0
  let height = 0
  let channels = 4
  const data: Buffer[] = []
  while (pos < file.length) {
    const length = file.readUInt32BE(pos)
    const type = file.toString('ascii', pos + 4, pos + 8)
    const body = file.subarray(pos + 8, pos + 8 + length)
    if (type === 'IHDR') {
      width = body.readUInt32BE(0)
      height = body.readUInt32BE(4)
      if (body[8] !== 8 || body[12] !== 0) throw new Error('only 8-bit, non-interlaced PNG files are read')
      channels = body[9] === 6 ? 4 : body[9] === 2 ? 3 : 0
      if (!channels) throw new Error(`PNG colour type ${body[9]} is not read`)
    } else if (type === 'IDAT') data.push(body)
    pos += 12 + length
  }
  const raw = inflateSync(Buffer.concat(data))
  const stride = width * channels
  const rgba = new Uint8Array(width * height * 4)
  let prev = new Uint8Array(stride)
  for (let y = 0; y < height; y++) {
    const row = Uint8Array.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)))
    unfilter(raw[y * (stride + 1)], row, prev, channels)
    for (let x = 0; x < width; x++) {
      rgba.set([row[x * channels], row[x * channels + 1], row[x * channels + 2], channels === 4 ? row[x * channels + 3] : 255], (y * width + x) * 4)
    }
    prev = row
  }
  return { width, height, rgba }
}

/** How many pixels differ in colour between two pictures of the same size; -1 when their sizes differ. */
export function differingPixels(a: Picture, b: Picture): number {
  if (a.width !== b.width || a.height !== b.height) return -1
  let n = 0
  for (let i = 0; i < a.rgba.length; i += 4) {
    if (a.rgba[i] !== b.rgba[i] || a.rgba[i + 1] !== b.rgba[i + 1] || a.rgba[i + 2] !== b.rgba[i + 2]) n++
  }
  return n
}
