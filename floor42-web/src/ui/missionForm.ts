/** A title proposed from the objective's first line, trimmed to the title length Floor42 reads comfortably. */
export function suggestTitle(objective: string, max = 60): string {
  const line = objective.split(/\r?\n/).find((l) => l.trim() !== '')?.trim() ?? ''
  if (line.length <= max) return line
  const cut = line.slice(0, max)
  const space = cut.lastIndexOf(' ')
  return (space > max / 2 ? cut.slice(0, space) : cut).trimEnd()
}

/** File paths the objective names (a folder and an extension, e.g. floor42-web/src/scene/x.ts), without repeats. */
export function namedPaths(objective: string): string[] {
  const found = objective.match(/[\w.-]+(?:\/[\w.-]+)+\.\w{1,6}\b/g) ?? []
  return [...new Set(found)]
}
