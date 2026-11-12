/**
 * The form two agent names compare in, as the daemon compares them (AgentsResource.sameName): NFKC, zero-width,
 * direction and variation marks removed, Unicode spaces collapsed, case folded.
 */
export function sameName(name: string): string {
  return name
    .normalize('NFKC')
    .replace(/[\u200B-\u200F\u2060\uFEFF]|\uFE0F|\u00AD|\u034F/gu, '')
    .replace(/[\p{Z}\s]+/gu, ' ')
    .trim()
    .toUpperCase()
    .toLowerCase()
}
