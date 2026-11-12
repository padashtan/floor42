import type { Words } from '../signs'

/** Invented companies and shops, for the neon signs. */
export const COMPANIES = ['NOVA BANK', 'HELIX LABS', 'ZENITH', 'ORBIT TELECOM', 'LUMEN', 'CIRRUS AIR', 'QUANTA', 'BYTE & CO', 'ATLAS', 'MERIDIAN']
const SHOPS = [
  'PIZZA', 'DELI', 'MOKA CAFÉ', 'BOOKS', 'FLOWERS', 'RAMEN', 'BAKERY', 'TACOS', 'VINYL', 'BAGELS', 'SUSHI', 'DINER',
  'BARBER', 'LAUNDRY', 'PHARMACY', 'GROCERY', 'JAZZ BAR', 'TOYS', 'SHOES', 'GELATO', 'ARCADE', 'HOTEL', 'CINEMA', 'GYM',
]
export const NEON = ['#ff3fd2', '#29f6ff', '#ffd319', '#6fe36a', '#ff6b3b', '#b98cff']
export const WORDS: Words = { shops: SHOPS, companies: COMPANIES, colors: NEON }
/** The dark plate behind the letters, and the steel of brackets and frames. */
export const PLATE_COLOR = 0x120a1c
export const IRON = 0x2b2d2f
/** Facade colours: brick, limestone, pastel paint, glass. The window texture is white, tinted per building. */
export const BRICK = [0x9b4a3a, 0x7a3f33, 0xb5654a, 0x8a5a44, 0xa04a5a, 0x6e4a3c, 0xc07a4a]
export const PAINT = [0xd9c27a, 0x8fbfa8, 0xd98f7a, 0x9fb4d9, 0xc9a0d0, 0xe0d0b0, 0xb9d98f, 0xf2b880]
export const GLASS = [0x5d7f99, 0x6f8fa6, 0x4f6d84, 0x5f9a9a, 0x7a86b0]
export const STONE = [0xcbbf9c, 0xb9ad8f, 0xd8cfb4, 0xc4b8a8]
/** Whitewashed walls and granite, for the Breton houses under their slate roofs. */
export const GRANITE = [0xece8de, 0xe0dacb, 0xcfc8b8, 0xf2efe6]
export const SLATE = [0x3d4550, 0x4a525c, 0x353b44, 0x525a66]
export const TILES = [0xa0522d, 0x8b4a2b, 0xb5653a, 0x6e4a3c, 0x5a3d2b]
/** Flat roofs: grey and black membranes, brown felt, pale gravel. */
export const MEMBRANE = [0x6b6e70, 0x2b2d2f, 0x5a4a3e, 0x9a968c, 0x7a7d80, 0x3f4245]
export const LEAVES = [0x2f7a3c, 0x3f8f4a, 0x4f9a3a, 0x2a6a3a, 0x5aa04a, 0x6a9a3a]
export const FIRS = [0x1f5a34, 0x2a6a40, 0x24503a]
export const BARK = [0x5a3d2b, 0x4a3426, 0x6b4a32]
/** Water tower staves: new cedar, weathered cedar gone grey-brown. */
export const CEDAR = [0xb08a62, 0xa07c5a, 0x9a8470, 0x8c7a68, 0xb89470]
