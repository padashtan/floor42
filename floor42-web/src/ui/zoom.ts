/** How an image of the viewer is drawn: fitted to the panel, or at a scale of its own size (1 is actual size). */
export type Zoom = 'fit' | number

/** The zoom steps, from a quarter to eight times the image's own size. */
export const STEPS: readonly number[] = [0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 8]

/**
 * The next step in `direction` (1 larger, -1 smaller), held at the ends. From "fit" zooming in starts at actual
 * size (the fitted scale depends on the panel); zooming out stays fitted, never jumps up.
 */
export function nextZoom(current: Zoom, direction: 1 | -1): Zoom {
  if (current === 'fit') return direction > 0 ? 1 : 'fit'
  if (direction > 0) return STEPS.find((s) => s > current) ?? STEPS[STEPS.length - 1]
  return [...STEPS].reverse().find((s) => s < current) ?? STEPS[0]
}
