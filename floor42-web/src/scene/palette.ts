// One constrained palette for the whole scene. Consistency of palette is what makes procedural art read as pixel art.
// Each theme (a decade) swaps it before the office is built: the furniture is the same, only its colours change.
const BASE = {
  void: 0x070f14,
  floorMetal: 0x2e5563,
  floorMetalDark: 0x24485a,
  floorLine: 0x16303a,
  raisedTile: 0x5f7178,
  raisedTileDark: 0x46565c,
  carpet: 0x6b3424,
  carpetDark: 0x52261a,
  wallPanel: 0xb9ad8f,
  wallPanelDark: 0x8f8670,
  wallTrim: 0x3a525d,
  wallBase: 0x24343b,
  steel: 0x5d6f78,
  steelDark: 0x3a4a52,
  chrome: 0x9fb0b6,
  beige: 0xcbbf9c,
  beigeDark: 0x9e9273,
  walnut: 0x5a3626,
  walnutDark: 0x3f2419,
  orange: 0xe0862c,
  hazard: 0xe8c547,
  red: 0xc8463c,
  redDark: 0x7e2622,
  cyan: 0x3fe0f0,
  cyanDim: 0x1d6f7a,
  green: 0x6fe36a,
  amber: 0xf2a93b,
  alarm: 0xff4b3e,
  plant: 0x3f8f4a,
  plantDark: 0x2a6234,
  pot: 0x7a5236,
  screenBg: 0x061a1f,
  // Floors of the open space, the conference room and the kitchen; the light of the sky, the ground and the sun.
  openRug: 0x3a4d57,
  openRugDark: 0x2e3f48,
  confCarpet: 0x34405e,
  confCarpetDark: 0x2a3450,
  kitchenTile: 0xd7d2c4,
  kitchenTileDark: 0xbdb6a5,
  sky: 0x9cc4d4,
  ground: 0x1c2c33,
  sun: 0xffe7c4,
};

export type PaletteKey = keyof typeof BASE;

/** The palette in force: changed in place by {@link applyTheme}, read by every prop when it is built. */
export const P: Record<PaletteKey, number> = { ...BASE };

export type ThemeId = '1980s' | '1990s' | '2000s' | '2020s';
export const THEME_IDS: ThemeId[] = ['1980s', '1990s', '2000s', '2020s'];

export interface Theme {
  /** Size of one rendered pixel, in screen pixels: chunky in the eighties, smooth today. */
  pixel: number;
  palette: Partial<Record<PaletteKey, number>>;
}

export const THEMES: Record<ThemeId, Theme> = {
  // Neon and synthwave: purple floors, magenta trims, cyan screens.
  '1980s': {
    pixel: 4,
    palette: {
      void: 0x12051f,
      floorMetal: 0x2a1240,
      floorMetalDark: 0x1e0b30,
      floorLine: 0xff2fd0,
      raisedTile: 0x3b1d5c,
      raisedTileDark: 0x2b1445,
      carpet: 0x5a1a6b,
      carpetDark: 0x3d1049,
      wallPanel: 0x2d2a5a,
      wallPanelDark: 0x1f1d40,
      wallTrim: 0xff3fd2,
      wallBase: 0x120a24,
      steel: 0x4b3f6e,
      steelDark: 0x2c2448,
      beige: 0x6a5f8a,
      beigeDark: 0x4a4068,
      walnut: 0x2a1d3d,
      walnutDark: 0x1a1028,
      orange: 0xff3fd2,
      red: 0xff2f6d,
      redDark: 0x8a1240,
      cyan: 0x29f6ff,
      amber: 0xffd319,
      screenBg: 0x0d0221,
      openRug: 0x24113f,
      openRugDark: 0x1a0b30,
      confCarpet: 0x3a0f4f,
      confCarpetDark: 0x2a0a3a,
      kitchenTile: 0x2b2b55,
      kitchenTileDark: 0xff2fd0,
      sky: 0xff7ae0,
      ground: 0x1a0730,
      sun: 0xffb0f0,
    },
  },
  // Beige computers, a station corridor, warm carpets: the office as it was first drawn.
  '1990s': { pixel: 3, palette: {} },
  // Glossy and bright, glass and brushed aluminium, sky blue everywhere.
  '2000s': {
    pixel: 2,
    palette: {
      void: 0x9fd6f2,
      floorMetal: 0xc9d6df,
      floorMetalDark: 0xb5c4cf,
      floorLine: 0x8fa6b6,
      raisedTile: 0xe4ecf2,
      raisedTileDark: 0xc8d4dd,
      carpet: 0x3d6fa8,
      carpetDark: 0x2f5a8a,
      wallPanel: 0xe8eef3,
      wallPanelDark: 0xc5d2dc,
      wallTrim: 0x5aa9e6,
      wallBase: 0x8aa4b8,
      steel: 0xa9b8c4,
      steelDark: 0x7d8f9c,
      chrome: 0xe2eaf0,
      beige: 0xdfe5ea,
      beigeDark: 0xb7c2cb,
      walnut: 0xc8ccd4,
      walnutDark: 0x9aa2ad,
      orange: 0x6ccff6,
      red: 0x3a8fd8,
      redDark: 0x1f5f9e,
      hazard: 0x8fd14f,
      openRug: 0x6f93b8,
      openRugDark: 0x5c80a5,
      confCarpet: 0x4a7fb5,
      confCarpetDark: 0x3d6c9c,
      kitchenTile: 0xf4f7fa,
      kitchenTileDark: 0xd9e2ea,
      sky: 0xd8f0ff,
      ground: 0x7aa0b8,
      sun: 0xffffff,
    },
  },
  // New age: light oak, sage, terracotta and plants; soft light, no pixels.
  '2020s': {
    pixel: 1,
    palette: {
      void: 0xe9ece4,
      floorMetal: 0xd9c7a8,
      floorMetalDark: 0xcbb894,
      floorLine: 0xbfa982,
      raisedTile: 0xeceae4,
      raisedTileDark: 0xdcd8cf,
      carpet: 0xa9b9a0,
      carpetDark: 0x95a68c,
      wallPanel: 0xf3f1ec,
      wallPanelDark: 0xdedad2,
      wallTrim: 0x8aa68a,
      wallBase: 0x6f7d6a,
      steel: 0x7c8a8f,
      steelDark: 0x5b666b,
      beige: 0xefe9de,
      beigeDark: 0xd2c9b8,
      walnut: 0xc9a57a,
      walnutDark: 0x9c7b55,
      orange: 0xf2cc8f,
      red: 0xe07a5f,
      redDark: 0xa8543f,
      plant: 0x5aa469,
      plantDark: 0x3e7d4c,
      pot: 0xd8cfc0,
      openRug: 0xd6d0c2,
      openRugDark: 0xc9c2b2,
      confCarpet: 0xb7c4b0,
      confCarpetDark: 0xa5b39e,
      kitchenTile: 0xf6f4ef,
      kitchenTileDark: 0xe4dfd5,
      sky: 0xffffff,
      ground: 0xd8d2c4,
      sun: 0xfff6e8,
    },
  },
};

let era: ThemeId = '1990s';

/** The decade in force: computers, screens and servers are built as they were then. */
export function currentEra(): ThemeId {
  return era;
}

/** Puts a theme's palette in force; the office must be built again to show it. */
export function applyTheme(id: ThemeId): Theme {
  const theme = THEMES[id] ?? THEMES['1990s'];
  era = THEMES[id] ? id : '1990s';
  Object.assign(P, BASE, theme.palette);
  return theme;
}

export const SKIN = [0xe2b48c, 0xb07a52, 0x7c4f33, 0xf0c9a5];
export const HAIR = [0x2a1d14, 0x6b4325, 0xd9c08a, 0x1a1a1a, 0x8a3a1c];
