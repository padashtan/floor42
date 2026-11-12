import { ref } from 'vue'
import type { MeetingPlace } from '../api/types'

/** The table the director right-clicked to call a meeting (ADR-0049): the meeting panel starts on it. */
export const callAt = ref<MeetingPlace>('TABLE')
