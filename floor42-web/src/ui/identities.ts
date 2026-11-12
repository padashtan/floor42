// Agent identity screens: pure helpers. Nothing here accepts or returns a raw token: only decoded header and claims,
// and the signature cut to 8 characters.
import { identityEn } from '../i18n/identity.en'
import { identityFr } from '../i18n/identity.fr'
import type { IamStatusView } from '../api/identity'
import { request } from '../api/request'
import { ref } from 'vue'
import type { Lang } from '../i18n'

const CATALOGUES: Record<Lang, Record<string, string>> = { en: identityEn, fr: identityFr }
/** A compact JWT (three base64url parts): anything shaped like one is removed from what the screen shows. */
const JWT = /eyJ[\w-]+\.[\w-]+\.[\w-]*/g
export const SIMULATED_ISSUER = 'urn:floor42:simulated'

export interface DisplayToken {
  header: Record<string, unknown>
  claims: Record<string, unknown>
  /** 8 characters and "...". */
  signature: string
  simulated: boolean
}

function scrub(value: unknown): unknown {
  if (typeof value === 'string') return value.replace(JWT, '[removed]')
  if (Array.isArray(value)) return value.map(scrub)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, scrub(v)]))
  return value
}

/** The token as the screen shows it: decoded header and claims, the signature cut to 8 characters plus "...". */
export function decodeForDisplay(header: object, claims: object, signaturePrefix: string): DisplayToken {
  const sig = signaturePrefix.replace(/\.+$/, '')
  return {
    header: scrub(header) as Record<string, unknown>,
    claims: scrub(claims) as Record<string, unknown>,
    signature: sig ? `${sig.slice(0, 8)}...` : '',
    simulated: (claims as Record<string, unknown>).iss === SIMULATED_ISSUER,
  }
}

function text(key: string, lang: Lang, params: Record<string, string> = {}): string {
  const raw = CATALOGUES[lang][key] ?? identityEn[key as keyof typeof identityEn] ?? key
  return raw.replace(/\{(\w+)\}/g, (all, name: string) => params[name] ?? all)
}

const asText = (v: unknown): string => (typeof v === 'string' ? v : '')

/** The agent a token names, without the client prefix ("floor42-agent-ada" is "ada"). */
export function agentOf(t: DisplayToken): string {
  const c = t.claims
  const azp = asText(c.azp) || asText((c.act as Record<string, unknown> | undefined)?.client_id)
  return azp.replace(/^floor42-agent-/, '').replace(/^simulated-/, '')
}

/** "ada acts for the director on M-3/T-7". */
export function chainText(t: DisplayToken, lang: Lang): string {
  const f = (t.claims.floor42 ?? {}) as Record<string, unknown>
  const [agent, mission, ticket] = [agentOf(t), asText(f.mission), asText(f.ticket)]
  if (!mission) return text('idn.chainTextBare', lang, { agent })
  return ticket ? text('idn.chainText', lang, { agent, mission, ticket }) : text('idn.chainTextMission', lang, { agent, mission })
}

/** What a refusal code means, in the screen's language; the code itself when it is new. */
export function refusalText(code: string, lang: Lang): string {
  const key = `idn.refusal.${code}`
  return key in identityEn ? text(key, lang) : code
}

/** The distinct missions of a list of tokens, for the audit export. */
export function missionsOf(tokens: { claims: Record<string, unknown> }[]): string[] {
  const ids = tokens.map((t) => asText(((t.claims.floor42 ?? {}) as Record<string, unknown>).mission)).filter(Boolean)
  return [...new Set(ids)]
}

/**
 * Whether IAM mode is on. Asked before any other /api/iam call: those answer 409 while it is off, and the browser logs
 * every failed request as a console error. An unreadable status counts as off.
 */
export async function iamOn(): Promise<boolean> {
  return request<IamStatusView>('GET', '/api/iam/status')
    .then((s) => s.enabled)
    .catch(() => false)
}

/** When the agents' Keycloak clients were last read successfully (this page load), for the outage line. */
export const lastAgentsRead = ref<string | null>(null)
