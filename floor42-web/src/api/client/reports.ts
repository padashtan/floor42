import type {
  ApiCall,
  EventView,
  CallDetail,
  UsageView,
  CostsOverview,
  MissionCosts,
  ProofDossier,
} from '../types'
import { request } from '../request'

export const reportsApi = {
  usage: () => request<UsageView>('GET', '/api/usage'),
  calls: (limit = 200) => request<ApiCall[]>('GET', `/api/calls?limit=${limit}`),
  call: (sequence: number) => request<CallDetail>('GET', `/api/calls/${sequence}`),
  events: (limit = 40) => request<EventView[]>('GET', `/api/events?limit=${limit}`),
  // cost-overview
  costs: (zone: string) => request<CostsOverview>('GET', `/api/costs?zone=${encodeURIComponent(zone)}`),
  missionCosts: (mission: string) =>
    request<MissionCosts>('GET', `/api/missions/${encodeURIComponent(mission)}/costs`),
  // proof-dossier (ADR-0071): always masked by the daemon
  proofDossier: (mission: string) =>
    request<ProofDossier>('GET', `/api/missions/${encodeURIComponent(mission)}/proof-dossier`),
}
