import * as THREE from 'three';
import { hash } from '../cover';
import { box, markTarget, mat } from '../kit';
import { currentEra, P } from '../palette';

/** The bookcase's footprint in tiles (width along x, depth along z): floorPlan.ts reserves it. */
export const BOOKCASE = { width: 1.1, depth: 0.4, height: 1.9 };
/** Shelves, and the books one shelf holds: a book is one project (the library of projects). */
const SHELVES = 4;
const PER_SHELF = 6;
/** The most spines the bookcase shows; the library panel lists them all. */
export const MAX_BOOKS = SHELVES * PER_SHELF;

/** The carcass of each decade: grey steel shelving, walnut, pale beech, white and black. */
const CARCASS = { '1980s': [P.steel, P.steelDark], '1990s': [P.walnut, P.walnutDark], '2000s': [0xd9c29c, 0xb89d70], '2020s': [0xf0f2f4, 0x2a2d33] } as const;

/**
 * The library's bookcase against the west wall of the open space: one book spine per project, painted with the
 * project's cover colours. Clicking it opens the library (target kind `library`). Spines face +z; the office turns it east.
 */
export class Bookcase {
  readonly root = new THREE.Group();
  private readonly books = new THREE.Group();
  /** The colours now on the shelves, in order: the digest and the tests read them. */
  colors: number[] = [];

  constructor() {
    const [wood, dark] = CARCASS[currentEra()];
    const { width: w, depth: d, height: h } = BOOKCASE;
    box(this.root, [0.05, h, d], [-w / 2 + 0.025, 0, 0], mat(wood));
    box(this.root, [0.05, h, d], [w / 2 - 0.025, 0, 0], mat(wood));
    box(this.root, [w, h, 0.03], [0, 0, -d / 2 + 0.015], mat(dark));
    for (let s = 0; s < SHELVES; s++) box(this.root, [w - 0.1, 0.04, d - 0.02], [0, 0.06 + s * 0.45, 0], mat(wood));
    box(this.root, [w, 0.06, d + 0.02], [0, h - 0.06, 0.01], mat(dark));
    this.root.add(this.books);
    markTarget(this.root, { kind: 'library', id: 'bookcase' });
  }

  /** One spine per colour, left to right, shelf by shelf from the bottom; replaces what stood there. */
  setBooks(colors: number[]): void {
    // A renderer listens to every material it drew until it is disposed, and the cloth materials are shared: the ones
    // leaving the shelf go with their geometry, or a replaced decade's renderer stays reachable through them.
    for (const child of [...this.books.children]) {
      const book = child as THREE.Mesh;
      book.geometry.dispose();
      (book.material as THREE.Material).dispose();
      this.books.remove(book);
    }
    this.colors = colors.slice(0, MAX_BOOKS);
    const { width: w, depth: d } = BOOKCASE;
    this.colors.forEach((color, i) => {
      const shelf = Math.floor(i / PER_SHELF);
      const slot = i % PER_SHELF;
      const tall = 0.26 + (hash(`${i}-${color}`) % 8) * 0.017;
      box(this.books, [0.13, tall, d - 0.14], [-w / 2 + 0.14 + slot * 0.155, 0.1 + shelf * 0.45, 0.06], mat(color));
    });
  }
}
