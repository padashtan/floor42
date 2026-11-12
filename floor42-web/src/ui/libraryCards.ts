import type { ProjectView } from '../api/types'
import { folderName } from './recent'
import { normalize } from './sectioned'

export type LibrarySection = 'all' | 'pinned' | 'gone'
export const LIBRARY_SECTIONS: readonly LibrarySection[] = ['all', 'pinned', 'gone']

/** A path shortened to fit a card: the end is what tells projects apart, so the middle goes first. */
export function shortPath(path: string, max = 40): string {
  if (path.length <= max) return path
  const keep = max - 1
  const head = Math.ceil(keep * 0.35)
  return `${path.slice(0, head)}…${path.slice(path.length - (keep - head))}`
}

/** The projects of a section (all, pinned, or whose folder is gone) that hold every word of the search. */
export function booksOf(projects: readonly ProjectView[], section: LibrarySection, query: string): ProjectView[] {
  const words = normalize(query).split(' ').filter(Boolean)
  return projects.filter((p) => {
    if (section === 'pinned' && !p.pinned) return false
    if (section === 'gone' && p.exists) return false
    const text = normalize(`${folderName(p.path)} ${p.path} ${p.lastTitle ?? ''} ${p.lastMission ?? ''}`)
    return words.every((w) => text.includes(w))
  })
}

/** How many projects each section holds (the search is not applied: these are the tab badges). */
export function sectionCounts(projects: readonly ProjectView[]): Record<LibrarySection, number> {
  return {
    all: projects.length,
    pinned: projects.filter((p) => p.pinned).length,
    gone: projects.filter((p) => !p.exists).length,
  }
}
