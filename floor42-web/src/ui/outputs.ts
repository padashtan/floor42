/**
 * Tool outputs shaped for reading, without changing a character of what was recorded: the file reader's line numbers
 * go to a gutter, a search's file names stand apart from the matching text, path lists use one separator. The raw
 * text stays one click away (copy, export, "raw").
 */
export type OutputShape =
  | { kind: 'numbered'; lines: { n: string; text: string }[] }
  | { kind: 'matches'; lines: { path: string; n: string | null; text: string }[] }
  | { kind: 'paths'; lines: string[] }
  | { kind: 'text'; text: string }

/** Read prints "   12\ttext" (or "12→text"); Grep prints "path:12:text", "path:text" or bare paths. */
export function shapeOutput(tool: string | null | undefined, text: string): OutputShape {
  const lines = text.replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n')
  if (tool === 'Read') {
    const numbered = lines.map((l) => /^\s*(\d+)(?:\t|→)(.*)$/.exec(l))
    if (numbered.length && numbered.every((m) => m)) {
      return { kind: 'numbered', lines: numbered.map((m) => ({ n: m![1], text: m![2] })) }
    }
  }
  if (tool === 'Glob' || tool === 'LS') {
    if (lines.every((l) => l && !/\s{2,}/.test(l))) return { kind: 'paths', lines: lines.map(slashes) }
  }
  if (tool === 'Grep') {
    const parsed = lines.map((l) => /^((?:[A-Za-z]:)?[^:\n]+?):(\d+)[:-](.*)$/.exec(l))
    if (parsed.length && parsed.every((m) => m)) {
      return { kind: 'matches', lines: parsed.map((m) => ({ path: slashes(m![1]), n: m![2], text: m![3] })) }
    }
    if (lines.every((l) => /^[\w./\\:-]+$/.test(l))) return { kind: 'paths', lines: lines.map(slashes) }
  }
  return { kind: 'text', text }
}

function slashes(p: string): string {
  return p.replace(/\\/g, '/')
}

/** What a tool call is, in a word the director recognises at a glance. */
export function toolFamily(tool: string, category: string): 'read' | 'search' | 'write' | 'shell' | 'web' | 'other' {
  if (tool === 'Read') return 'read'
  if (tool === 'Grep' || tool === 'Glob' || tool === 'LS') return 'search'
  if (tool === 'Write' || tool === 'Edit' || tool === 'MultiEdit' || category === 'WRITE') return 'write'
  if (tool === 'Bash' || category === 'EXECUTE') return 'shell'
  if (category === 'NETWORK') return 'web'
  return 'other'
}
