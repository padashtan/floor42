import { ref } from 'vue'
import { en } from './en'
import { fr } from './fr'
import { identityEn } from './identity.en'
import { identityFr } from './identity.fr'
import { unsafeEn } from './unsafe.en'
import { unsafeFr } from './unsafe.fr'

/**
 * The screen's language, chosen by each viewer and kept in this browser only: nothing about it reaches the journal.
 * Texts written by agents and commands are shown as recorded, whatever the language.
 */
export type Lang = 'en' | 'fr'
export type Key = keyof typeof en | keyof typeof identityEn | keyof typeof unsafeEn

/** The identity and unsafe-mode catalogues are merged here, beside en.ts and fr.ts, which merge the other areas. */
const CATALOGS: Record<Lang, Record<Key, string>> = {
  en: { ...en, ...identityEn, ...unsafeEn },
  fr: { ...fr, ...identityFr, ...unsafeFr },
}
const STORAGE = 'floor42.lang'

function initial(): Lang {
  try {
    const saved = localStorage.getItem(STORAGE)
    if (saved === 'en' || saved === 'fr') return saved
  } catch {
    // No storage (private window, blocked site data): the browser's language decides.
  }
  return globalThis.navigator?.language?.toLowerCase().startsWith('fr') ? 'fr' : 'en'
}

/** The page's own lang attribute, for screen readers and hyphenation; absent outside a browser (tests). */
function markPage(l: Lang): void {
  if (typeof document !== 'undefined') document.documentElement.lang = l
}

export const lang = ref<Lang>(initial())
markPage(lang.value)

export function setLang(l: Lang): void {
  lang.value = l
  markPage(l)
  try {
    localStorage.setItem(STORAGE, l)
  } catch {
    // Kept for this page only.
  }
}

/**
 * The locale dates and times are written in: the page's language, not the browser's (a French page showed
 * "09:20:05 PM"). Read in templates and computeds, so a change of language rewrites them.
 */
export function dateLocale(): string {
  return lang.value === 'fr' ? 'fr-FR' : 'en-GB'
}

/** The text for a key in the current language, with {name} placeholders filled. Reactive in templates. */
export function t(key: Key, params: Record<string, string | number> = {}): string {
  const text = CATALOGS[lang.value][key] ?? CATALOGS.en[key]
  return text.replace(/\{(\w+)\}/g, (all, name: string) => (name in params ? String(params[name]) : all))
}

const DESPITE = ' despite the critical objection'

/**
 * An answer the server offers ("Accept as is", "ALLOW"…) in the screen's language. Only the label changes: the
 * option sent back is always the server's own text.
 */
export function optionLabel(option: string): string {
  if (option.endsWith(DESPITE)) return t('option.despite', { option: optionLabel(option.slice(0, -DESPITE.length)) })
  return tr(`option.${option}`, option)
}

/** A key built at run time (a status, a room id): the English text, or the given fallback, when it is unknown. */
export function tr(key: string, fallback: string, params: Record<string, string | number> = {}): string {
  return key in CATALOGS.en ? t(key as Key, params) : fallback
}
