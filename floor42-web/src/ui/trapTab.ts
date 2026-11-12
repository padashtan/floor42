/** Keeps Tab (Shift+Tab) inside `box`, cycling over the elements matching `selector`; call it from a keydown on Tab. */
export function trapTab(box: HTMLElement | undefined, e: KeyboardEvent, selector: string): void {
  const items = [...(box?.querySelectorAll<HTMLElement>(selector) ?? [])]
  const i = items.indexOf(document.activeElement as HTMLElement)
  const target = items[(i + (e.shiftKey ? -1 : 1) + items.length) % items.length]
  e.preventDefault()
  target?.focus()
}
