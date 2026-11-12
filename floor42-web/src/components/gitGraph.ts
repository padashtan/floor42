// Lanes of a git graph, computed without the DOM: one lane per line of history, commits as dots on their lane,
// branchings and merges as lines between lanes. Pure, so it is tested on its own (no Vue, no SVG).

export interface GraphInput {
  hash: string
  /** First parent first. A parent missing from the list (graph truncated) runs off the bottom. */
  parents: string[]
}

/**
 * A straight piece of line between two points (row, lane); `color` is the lane whose colour it takes. A row equal to
 * the number of commits is below the last one: the line runs off the bottom.
 */
export interface Segment {
  fromRow: number
  fromLane: number
  toRow: number
  toLane: number
  color: number
}

export interface GraphLayout {
  /** The lane of each commit, in the order given. */
  lanes: number[]
  segments: Segment[]
  /** How many lanes are drawn: the width of the graph. */
  width: number
}

interface Line {
  /** The commit the line goes to. */
  hash: string
  row: number
  from: number
  /** The lane it runs along. */
  via: number
  /** The line opened a lane of its own (a branch, a merge's other parent): its fork takes that lane's colour. */
  opened: boolean
}

/**
 * Lays out commits given children before parents (git log --topo-order). `heads` are the branch tips in the order
 * their lanes are wanted: the first (the director's branch) gets lane 0, the next lane 1, and so on; a head's lane
 * starts at its tip, never above it. A lane keeps its place until its line of history ends: lanes never move sideways,
 * a line changes lane only when it forks out of a commit or joins one.
 */
export function layout(commits: GraphInput[], heads: string[] = []): GraphLayout {
  const known = new Set(commits.map((c) => c.hash))
  // slots[k]: the commit lane k waits for next, or null when free.
  const slots: (string | null)[] = []
  // A head's lane is kept for it until its tip is reached.
  const reserved = new Map<string, number>()
  for (const h of heads) {
    if (!known.has(h) || reserved.has(h)) continue
    reserved.set(h, slots.length)
    slots.push(null)
  }
  const free = (): number => {
    const held = new Set(reserved.values())
    const i = slots.findIndex((s, k) => s === null && !held.has(k))
    if (i >= 0) return i
    slots.push(null)
    return slots.length - 1
  }

  const lanes: number[] = []
  const segments: Segment[] = []
  let open: Line[] = []

  const draw = (l: Line, toRow: number, toLane: number) => {
    const start = l.from === l.via ? l.row : l.row + 1
    const end = toLane === l.via ? toRow : toRow - 1
    if (start > end) {
      segments.push({ fromRow: l.row, fromLane: l.from, toRow, toLane, color: l.via })
      return
    }
    if (l.from !== l.via) {
      segments.push({ fromRow: l.row, fromLane: l.from, toRow: start, toLane: l.via, color: l.opened ? l.via : l.from })
    }
    if (end > start) segments.push({ fromRow: start, fromLane: l.via, toRow: end, toLane: l.via, color: l.via })
    if (toLane !== l.via) segments.push({ fromRow: end, fromLane: l.via, toRow, toLane, color: l.via })
  }

  commits.forEach((c, row) => {
    // Every lane waiting for this commit ends on it. A head sits on its own lane, anyone else on the lowest waiting
    // lane: the other lines join it there.
    const waiting = slots.flatMap((s, k) => (s === c.hash ? [k] : []))
    const lane = reserved.has(c.hash) ? reserved.get(c.hash)! : waiting.length > 0 ? waiting[0] : free()
    reserved.delete(c.hash)
    waiting.forEach((k) => (slots[k] = null))
    lanes.push(lane)
    const still: Line[] = []
    for (const l of open) {
      if (l.hash === c.hash) draw(l, row, lane)
      else still.push(l)
    }
    open = still

    c.parents.forEach((parent, i) => {
      const already = slots.indexOf(parent)
      if (i === 0 && slots[lane] === null) {
        // The first parent continues the commit's own lane, even when another lane waits for it too: both lines meet
        // at the parent, on the lowest of their lanes.
        slots[lane] = parent
        open.push({ hash: parent, row, from: lane, via: lane, opened: false })
      } else if (already >= 0) {
        // A merge's other parent that a lane already waits for: the line joins that lane.
        open.push({ hash: parent, row, from: lane, via: already, opened: false })
      } else {
        const target = free()
        slots[target] = parent
        open.push({ hash: parent, row, from: lane, via: target, opened: true })
      }
    })
  })

  // Lines to parents that are not shown run off the bottom of the graph, along their lane.
  for (const l of open) draw(l, commits.length, l.via)
  const width = Math.max(0, ...lanes.map((l) => l + 1), ...segments.map((s) => Math.max(s.fromLane, s.toLane) + 1))
  return { lanes, segments, width }
}
