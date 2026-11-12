import { ref } from 'vue'

/** Copy to the clipboard and remember which text was copied last, so its button can say "Copied". */
export function useCopy() {
  const copied = ref<string | null>(null)
  async function copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text)
      copied.value = text
    } catch {
      copied.value = null
    }
  }
  return { copied, copy }
}
