import type { MachineId } from '../scene/mapping'

/** What each machine stands for. Shown to the director; kept next to the components that display it. */
export const MACHINES: Record<MachineId, { name: string; stands: string; categories: string[] }> = {
  'terminal-1': { name: 'TERMINAL-01', stands: 'Shell commands and file edits', categories: ['EXECUTE', 'WRITE'] },
  'mainframe-1': { name: 'MAINFRAME-70', stands: 'Builds and test runs', categories: ['EXECUTE'] },
  'archive-1': { name: 'ARCHIVE READER', stands: 'Reading and searching files', categories: ['READ'] },
  'printer-1': { name: 'LINE PRINTER', stands: 'Deliverables submitted for review', categories: [] },
}
