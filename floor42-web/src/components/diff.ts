export interface DiffLine {
  kind: 'meta' | 'hunk' | 'add' | 'remove' | 'context'
  text: string
}

/** Classifies the lines of a unified diff for display; git's file headers are kept but shown as metadata. */
export function diffLines(diff: string): DiffLine[] {
  const lines = diff.replace(/\r\n/g, '\n').split('\n')
  if (lines.at(-1) === '') lines.pop()
  let inHunk = false
  return lines.map((text) => {
    if (text.startsWith('@@')) {
      inHunk = true
      return { kind: 'hunk', text }
    }
    if (text.startsWith('diff --git')) inHunk = false
    if (!inHunk) return { kind: 'meta', text }
    if (text.startsWith('+')) return { kind: 'add', text }
    if (text.startsWith('-')) return { kind: 'remove', text }
    return { kind: 'context', text }
  })
}
