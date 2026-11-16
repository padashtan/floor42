import type {
  CatalogueView,
  MissionAssetsView,
  PluginOutcome,
  PluginPreview,
  PluginRequest,
  PluginsView,
} from '../types'
import { request } from '../request'

export const pluginsApi = {
  /** Skills, agents and plugins (W3): read from disk now, nothing journaled, nothing installed. */
  catalogue: (mission: string | null) =>
    request<CatalogueView>('GET', mission ? `/api/catalogue?mission=${encodeURIComponent(mission)}` : '/api/catalogue'),
  /** Marketplaces and installed plugins, read from disk now (ADR-0040): no network, nothing installed. */
  plugins: () => request<PluginsView>('GET', '/api/plugins'),
  /** Reads a marketplace's listing from GitHub: a network call, on the director's click only. */
  refreshMarketplace: (name: string) =>
    request<PluginsView>('POST', `/api/plugins/marketplaces/${encodeURIComponent(name)}/refresh`),
  /** What an install or uninstall would run: runs nothing. */
  previewPlugin: (r: PluginRequest) => request<PluginPreview>('POST', '/api/plugins/preview', r),
  /** Runs what the director confirmed (the preview's digest), then reads the installed state again. */
  runPlugin: (r: PluginRequest) => request<PluginOutcome>('POST', '/api/plugins/run', r),
  missionAssets: (mission: string) =>
    request<MissionAssetsView>('GET', `/api/missions/${encodeURIComponent(mission)}/assets`),
  /** The skills and agents the mission's sessions receive from now on; journaled. */
  selectMissionAssets: (mission: string, items: string[]) =>
    request<MissionAssetsView>('PUT', `/api/missions/${encodeURIComponent(mission)}/assets`, { items }),
}
