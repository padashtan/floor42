import type { AgentIdentityView, IamStatusView } from '../api/identity'
import { request } from '../api/request'
import type { ClientState, IdentityHealth } from '../scene/props'

/** What the identity server in the scene shows: IAM mode's health, and one light per agent's Keycloak client. */
export interface IdentityLights {
  health: IdentityHealth
  clients: ClientState[]
}

export function clientState(a: AgentIdentityView): ClientState {
  if (a.revoked) return 'revoked'
  return a.clientId && a.enabled ? 'ready' : 'missing'
}

/** Read from the daemon; the clients only while IAM mode is up (the route refuses otherwise). Null when unreadable. */
export async function readIdentityLights(): Promise<IdentityLights | null> {
  try {
    const status = await request<IamStatusView>('GET', '/api/iam/status')
    if (status.health !== 'UP') return { health: status.health, clients: [] }
    const agents = await request<AgentIdentityView[]>('GET', '/api/iam/agents')
    return { health: 'UP', clients: agents.map(clientState) }
  } catch {
    return null
  }
}
