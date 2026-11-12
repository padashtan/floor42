/**
 * The command palette's search: every word of the query must appear in the text, its letters in order (gaps allowed),
 * accents and case ignored. Letters starting a word and letters following each other score more, so "bo" finds
 * "Board" before "Big room". Pure functions, tested without a browser.
 */

/** Lower case, accents dropped: "Équipe" and "equipe" are the same. */
export function fold(s: string): string {
  return s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
}

function startsWord(text: string, i: number): boolean {
  return i === 0 || !/[\p{L}\p{N}]/u.test(text[i - 1])
}

/** One word against a folded text: its score, or null when its letters are not all there in order (or, strict, together). */
function word(query: string, text: string, strict: boolean): number | null {
  if (!query) return 0
  const at = text.indexOf(query)
  if (at >= 0) return 10 + query.length * 3 + (startsWord(text, at) ? 8 : 0) + (at === 0 ? 4 : 0)
  if (strict) return null
  let score = 0
  let from = 0
  let previous = -2
  for (const c of query) {
    const i = text.indexOf(c, from)
    if (i < 0) return null
    score += 1 + (startsWord(text, i) ? 3 : 0) + (i === previous + 1 ? 2 : 0)
    previous = i
    from = i + 1
  }
  return score
}

/**
 * The query's score against the text, or null when a word of the query does not match. An empty query scores 0.
 * Strict: each word must appear whole, letters together (for long descriptions, where scattered letters match anything).
 */
export function fuzzyScore(query: string, text: string, strict = false): number | null {
  const folded = fold(text)
  let total = 0
  for (const w of fold(query).split(/\s+/).filter(Boolean)) {
    const s = word(w, folded, strict)
    if (s === null) return null
    total += s
  }
  return total
}

/** A match on the item's own name beats one found only in its description. */
const PRIMARY_BONUS = 5

/**
 * The items that match, best first; equal scores keep the given order. An empty query returns them all. `text` gives
 * the item's name, then optionally more text to search (its description, its id): the name matches loosely and scores a
 * little more, the rest only by whole words of the query.
 */
export function rank<T>(
  items: readonly T[],
  query: string,
  text: (item: T) => [string, ...string[]],
  limit = Infinity,
): T[] {
  const score = (item: T): number | null => {
    const [name, ...more] = text(item)
    const own = fuzzyScore(query, name)
    const all = fuzzyScore(query, [name, ...more].join(' '), true)
    if (own === null) return all
    return Math.max(own + PRIMARY_BONUS, all ?? 0)
  }
  return items
    .map((item, index) => ({ item, index, score: score(item) }))
    .filter((r): r is { item: T; index: number; score: number } => r.score !== null)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .slice(0, limit)
    .map((r) => r.item)
}
