import type { InjectionKey, Ref } from 'vue'

/** The meeting room's single busy flag: one write at a time, whichever part of the room started it. */
export const MEETING_BUSY: InjectionKey<Ref<boolean>> = Symbol('meeting-busy')
