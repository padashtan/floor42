import { wanted } from '../state/library'
import { putOnBoard } from '../state/office'
import { reportOf, setPanel } from '../state/openPanels'
import type { TodoGo } from './todo'
import { proposeQuarantine } from './useAgentConfirm'

/**
 * Opens the place a to-do row leads to (the same targets the To do panel's button opens); the desk is the caller's.
 * An approval acts on the board's mission: that mission goes on the board first, else the place shows another one.
 */
export function openTodoGo(g: TodoGo, openDesk: (question: string) => void): void {
  if (g.panel === 'desk') openDesk(g.question)
  else if (g.panel === 'quarantine') void proposeQuarantine(g.mission, g.agent)
  else if (g.panel === 'report') reportOf.value = g.mission
  else if (g.panel === 'plan') {
    void putOnBoard(g.mission)
    wanted.missionTab = true
    setPanel('desk', true)
  } else {
    if (g.panel === 'board') void putOnBoard(g.mission)
    setPanel(g.panel, true)
  }
}
