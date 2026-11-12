// Renders every docs/architecture/png/*.svg.html page written by build.py to a PNG beside it, with headless Chrome,
// then stores it as an 8-bit palette PNG (a diagram has few colours; the file is about a third of the screenshot).
// Playwright is not a dependency of this folder: run with NODE_PATH pointing at a node_modules that has it, e.g.
//   NODE_PATH=floor42-web/node_modules node tools/diagrams/render.mjs
// FLOOR42_CHROME names the browser executable (default: the installed Chrome's channel).
import { createRequire } from 'node:module';
import { readdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import zlib from 'node:zlib';

const require = createRequire(import.meta.url);
const modules = (process.env.NODE_PATH || '').split(/[;:](?![\\/])/).filter(Boolean);
let playwright;
for (const m of [...modules.map((p) => join(p, 'playwright')), 'playwright']) {
  try {
    playwright = require(m);
    break;
  } catch {
    // Handled: try the next place; none found fails below.
  }
}
if (!playwright) throw new Error('playwright not found: set NODE_PATH to a node_modules that has it');

// ---- PNG: decode Chrome's RGBA screenshot, write a palette PNG

function chunks(buf) {
  const out = [];
  for (let p = 8; p < buf.length; ) {
    const len = buf.readUInt32BE(p);
    out.push({ type: buf.toString('ascii', p + 4, p + 8), data: buf.subarray(p + 8, p + 8 + len) });
    p += 12 + len;
  }
  return out;
}

function decode(buf) {
  const cs = chunks(buf);
  const ih = cs.find((c) => c.type === 'IHDR').data;
  const w = ih.readUInt32BE(0), h = ih.readUInt32BE(4), depth = ih[8], type = ih[9];
  if (depth !== 8 || (type !== 6 && type !== 2) || ih[12] !== 0) throw new Error('unexpected PNG format from Chrome');
  const bpp = type === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(cs.filter((c) => c.type === 'IDAT').map((c) => c.data)));
  const stride = w * bpp, px = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)], src = y * (stride + 1) + 1, dst = y * stride;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? px[dst + x - bpp] : 0, b = y ? px[dst - stride + x] : 0;
      const c = x >= bpp && y ? px[dst - stride + x - bpp] : 0;
      let v = raw[src + x];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      px[dst + x] = v & 255;
    }
  }
  return { w, h, bpp, px };
}

function crcChunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(zlib.crc32(Buffer.concat([head.subarray(4), data])) >>> 0);
  return Buffer.concat([head, data, crc]);
}

function palettePng({ w, h, bpp, px }) {
  const count = new Map();
  for (let i = 0; i < w * h; i++) {
    const k = (px[i * bpp] << 16) | (px[i * bpp + 1] << 8) | px[i * bpp + 2];
    count.set(k, (count.get(k) || 0) + 1);
  }
  const pal = [...count.entries()].sort((a, b) => b[1] - a[1]).slice(0, 256).map((e) => e[0]);
  const near = new Map();
  const index = (k) => {
    let n = near.get(k);
    if (n !== undefined) return n;
    let best = 1e9;
    const r = k >> 16, g = (k >> 8) & 255, b = k & 255;
    pal.forEach((q, j) => {
      const d = (r - (q >> 16)) ** 2 + (g - ((q >> 8) & 255)) ** 2 + (b - (q & 255)) ** 2;
      if (d < best) [best, n] = [d, j];
    });
    near.set(k, n);
    return n;
  };
  const rows = Buffer.alloc(h * (w + 1));
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * bpp;
      rows[y * (w + 1) + 1 + x] = index((px[i] << 16) | (px[i + 1] << 8) | px[i + 2]);
    }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr.set([8, 3, 0, 0, 0], 8);
  const plte = Buffer.alloc(pal.length * 3);
  pal.forEach((q, j) => plte.set([q >> 16, (q >> 8) & 255, q & 255], j * 3));
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    crcChunk('IHDR', ihdr),
    crcChunk('PLTE', plte),
    crcChunk('IDAT', zlib.deflateSync(rows, { level: 9 })),
    crcChunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---- render

const dir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'docs', 'architecture', 'png');
const pages = readdirSync(dir).filter((f) => f.endsWith('.svg.html')).sort();
const exe = process.env.FLOOR42_CHROME;
const browser = await playwright.chromium.launch(exe ? { executablePath: exe } : { channel: 'chrome' });
const page = await browser.newPage({ deviceScaleFactor: 1 });
for (const f of pages) {
  await page.goto(pathToFileURL(join(dir, f)).href);
  const box = await page.locator('svg').boundingBox();
  await page.setViewportSize({ width: Math.ceil(box.width), height: Math.ceil(box.height) });
  const shot = await page.locator('svg').screenshot();
  writeFileSync(join(dir, f.replace('.svg.html', '.png')), palettePng(decode(shot)));
}
await browser.close();
for (const f of pages) unlinkSync(join(dir, f));
console.log(`${pages.length} PNG written to docs/architecture/png`);
