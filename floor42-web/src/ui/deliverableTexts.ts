import type { DeliverablesView, DeliveredBranch } from '../api/types'
import { t, tr } from '../i18n'
import { counts } from './deliverables'

/** The texts of the deliverables panel (ADR-0037) that read a view only: how a branch reached the project, and where. */
export const merged = (b: DeliveredBranch) =>
  tr(`deliverables.merged.${b.merged}`, b.merged) + (b.notInProject > 0 ? ' · ' + t('deliverables.notInProject', { count: b.notInProject }) : '')

/** The count of committed and uncommitted files of a branch. */
export function summary(b: DeliveredBranch): string {
  const c = counts(b)
  return t('deliverables.branchSummary', { committed: c.committed, uncommitted: c.uncommitted })
}

/** The project's folder, branch and head, or why it cannot be read. */
export function where(v: DeliverablesView): string {
  if (v.available) {
    return t('deliverables.projectAt', { path: v.repository ?? '', branch: v.projectBranch || t('project.detached'), head: v.projectHead ?? '?' })
  }
  return v.repository ? t('archive.unreadable', { path: v.repository }) : t('archive.noRepository')
}
