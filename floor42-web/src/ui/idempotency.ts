/**
 * One Idempotency-Key per content sent: the same content sent again (a retry after a lost answer) reuses its key, so
 * the daemon creates one mission; an edited form gets a new key, never the 409 IDEMPOTENCY_KEY_REUSED of the old one.
 * `done` forgets the key once the mission exists: the same content proposed again is a new mission.
 */
export function keyPerContent(newKey: () => string) {
  let content: string | null = null
  let key = ''
  return {
    keyFor(next: unknown): string {
      const text = JSON.stringify(next)
      if (text !== content) {
        content = text
        key = newKey()
      }
      return key
    },
    done(): void {
      content = null
    },
  }
}
