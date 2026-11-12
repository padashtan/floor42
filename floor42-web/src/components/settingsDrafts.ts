// settings-redesign: the values the director typed in the settings panel and has not saved yet, and why the daemon
// refused the last one. Module state, so closing the panel loses nothing; screen state only, never journaled.
import { reactive } from 'vue'
import { ApiError } from '../api/client'
import type { SettingView } from '../api/types'
import { t, tr } from '../i18n'

export interface SettingsDrafts {
  /** Unsaved values, by key. */
  values: Record<string, string>
  /** The daemon's refusal of the last save, phrased for the screen, by key. */
  refusals: Record<string, string>
}

export const settingsDrafts: SettingsDrafts = reactive({ values: {}, refusals: {} })

/** A value typed in an editor: a draft, unless it is the value in force. A new value clears the last refusal. */
export function setDraft(store: SettingsDrafts, key: string, value: string, inForce: string | undefined): void {
  if (value === inForce) delete store.values[key]
  else store.values[key] = value
  delete store.refusals[key]
}

/** After a reload: a draft equal to the value now in force is no longer a change. */
export function prune(store: SettingsDrafts, settings: readonly SettingView[]): void {
  for (const s of settings) if (store.values[s.key] === s.value) delete store.values[s.key]
}

/** Writes one setting; resolves to the error when the daemon refused it, else null. */
export type SettingWrite = (key: SettingView['key'], value: string | null) => Promise<unknown>

/**
 * Saves a value (null: back to the default). Saved: its draft and refusal go. Refused: the draft stays, so nothing
 * typed is lost, and the refusal is kept beside it, phrased. Returns whether the daemon took it.
 */
export async function saveSetting(
  store: SettingsDrafts,
  key: SettingView['key'],
  value: string | null,
  write: SettingWrite,
): Promise<boolean> {
  const error = await write(key, value)
  if (error === null || error === undefined) {
    delete store.values[key]
    delete store.refusals[key]
    return true
  }
  store.refusals[key] = refusalText(key, error)
  return false
}

/**
 * A refusal in the screen's language: what the setting accepts when the value was out of bounds, else the daemon's
 * reason after a sentence of ours. Never the raw "CODE: message" line.
 */
export function refusalText(key: string, error: unknown): string {
  const reason = error instanceof Error ? error.message : String(error)
  if (error instanceof ApiError && error.code === 'INVALID_ARGUMENT') {
    return tr(`sr.refused.${key}`, t('sr.refused', { reason }))
  }
  return t('sr.refusedOther', { reason })
}
