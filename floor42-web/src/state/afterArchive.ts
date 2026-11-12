import { ref } from 'vue'

/** The id of the mission just archived while the dialog asks what the board shows next (AfterArchiveDialog.vue). */
export const offer = ref<string | null>(null)

export const offerAfterArchive = (id: string): void => void (offer.value = id)
