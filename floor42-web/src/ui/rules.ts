import { tr } from '../i18n'

/** The rule beyond a tool's line (rulefor.*), in the screen's language, with its decision named. */
export const ruleFor = (code: string, params: Record<string, string>) =>
  tr(`rulefor.${code}`, code, { ...params, decision: tr(`perm.${params.decision}`, params.decision) })
