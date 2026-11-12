/**
 * Office became Floor42 in 1.5.0: what this browser kept under `office.*` (language, panel places, filters, the last
 * acknowledged event, notifications) moves once to `floor42.*`, quietly. A value already kept under the new name wins.
 */
const OLD = 'office.'
const NEW = 'floor42.'

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key' | 'length'>

export function migrateLegacyKeys(store: Store | null): number {
  if (!store) return 0
  let moved = 0
  try {
    const old: string[] = []
    for (let i = 0; i < store.length; i++) {
      const k = store.key(i)
      if (k?.startsWith(OLD)) old.push(k)
    }
    for (const k of old) {
      const value = store.getItem(k)
      const renamed = NEW + k.slice(OLD.length)
      if (value !== null && store.getItem(renamed) === null) {
        store.setItem(renamed, value)
        moved++
      }
      store.removeItem(k)
    }
  } catch {
    // Storage blocked or full: the old values are simply not carried over.
  }
  return moved
}

function storage(): Store | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

migrateLegacyKeys(storage())
