// Logic shared by the panels built on SectionedPanel.vue (sections on the left, an optional search): the search and the
// arrow keys. Nothing here knows what a section holds.

/** Lower case, accents and extra spaces removed: "Réseau" is found by "reseau". */
export function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * The sections holding at least one item whose text has every word of the query, each with those items only. An
 * empty query keeps everything.
 */
export function filterSections<S extends { items: unknown[] }>(
  sections: readonly S[],
  query: string,
  textOf: (item: S['items'][number]) => string,
): S[] {
  const words = normalize(query).split(' ').filter(Boolean)
  if (!words.length) return [...sections]
  return sections
    .map((s) => ({
      ...s,
      items: s.items.filter((item) => {
        const text = normalize(textOf(item))
        return words.every((w) => text.includes(w))
      }),
    }))
    .filter((s) => s.items.length > 0)
}

/** The section a key moves to (arrows, Home, End; wrapping around), or null for any other key. */
export function moveTo(ids: readonly string[], current: string, key: string): string | null {
  const i = ids.indexOf(current)
  const by = key === 'ArrowDown' || key === 'ArrowRight' ? 1 : key === 'ArrowUp' || key === 'ArrowLeft' ? -1 : 0
  if (by) return ids[(i + by + ids.length) % ids.length]
  if (key === 'Home') return ids[0]
  if (key === 'End') return ids[ids.length - 1]
  return null
}
