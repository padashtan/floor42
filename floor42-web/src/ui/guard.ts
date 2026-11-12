import { ref } from 'vue'

/**
 * Runs one action at a time with `acting` true while it runs, whatever happens (an error, a refusal): the state lives
 * with whoever creates the guard, so a component that unmounts mid-action cannot leave it stuck.
 */
export function useGuard() {
  const acting = ref(false)
  async function guard(work: () => Promise<unknown>): Promise<boolean> {
    acting.value = true
    try {
      return !!(await work())
    } finally {
      acting.value = false
    }
  }
  return { acting, guard }
}
