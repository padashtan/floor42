// The daemon's calls, one file per domain in ./client (the same domains as types.*.ts). `api` keeps its flat shape.
import { officeApi } from './client/office'
import { projectsApi } from './client/projects'
import { missionsApi } from './client/missions'
import { ticketsApi } from './client/tickets'
import { deliveryApi } from './client/delivery'
import { meetingsApi } from './client/meetings'
import { pluginsApi } from './client/plugins'
import { settingsApi } from './client/settings'
import { reportsApi } from './client/reports'
import { workflowsApi } from './client/workflows'
import { ApiError, REQUEST_HEADER } from './request'

export { ApiError, REQUEST_HEADER }
export { MISSION_TITLE_MAX, MISSION_OBJECTIVE_MAX, newIdempotencyKey } from './client/missions'
export { deliverableUrl, deliverablesZipUrl } from './client/delivery'
export type { DeliverableRef } from './client/delivery'

export const api = {
  ...officeApi,
  ...projectsApi,
  ...missionsApi,
  ...ticketsApi,
  ...deliveryApi,
  ...meetingsApi,
  ...pluginsApi,
  ...settingsApi,
  ...reportsApi,
  ...workflowsApi,
}
