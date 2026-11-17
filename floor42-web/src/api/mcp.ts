// Governed external MCP servers (IAM mode): the views of /api/iam/mcp-servers. Hand-written from the shapes of the
// server sheet (design-1-governed-mcp.md); contract.ts ties them to openapi.json once that is regenerated.
import { followStepUp, request } from './request'

export interface McpToolView {
  name: string
  /** Text written by the remote server: never trusted, never rendered as markup. */
  description: string | null
  inputSchema: Record<string, unknown> | null
  sha256: string
  ticked: boolean
  /** The definition differs from the one the director allowed: the tool is unticked until allowed again. */
  changed: boolean
}

export interface McpServerView {
  resource: string
  path: string
  /** The catalogue resource's address and audience as pinned with the tools. */
  baseUrl: string
  audience: string
  /** When the tools were last read from the server; null before the first refresh. */
  pinnedAt: string | null
  tools: McpToolView[]
}

/** GET /api/iam/mcp-servers, and DELETE (the list left). */
export interface McpServerList {
  servers: McpServerView[]
}

export interface McpTryResult {
  ok: boolean
  firstLine: string
  body: string
  truncated: boolean
  /** The code of a refusal (HOST_NOT_ALLOWED, RESOURCE_NOT_GRANTED...), null when the call went through. */
  refusal: string | null
}

const base = '/api/iam/mcp-servers'
const of = (resource: string) => `${base}/${encodeURIComponent(resource)}`

/** A tick carries the hash of the definition the director saw: a changed tool answers 400 MCP_TOOL_CHANGED. */
export interface McpTick {
  name: string
  sha256: string
}

export const mcpApi = {
  list: () => request<McpServerList>('GET', base),
  declare: (resource: string, path: string) => request<McpServerView>('PUT', base, { resource, path }),
  remove: (resource: string) => request<McpServerList>('DELETE', of(resource)),
  /** Reads the tools with the chosen agent's own token, for the chosen mission, on the director's behalf. */
  refresh: (resource: string, agent: string, mission: string) => request<McpServerView>('POST', `${of(resource)}/tools/refresh`, { agent, mission }),
  tick: (resource: string, ticks: McpTick[]) =>
    request<McpServerView | { redirect: string }>('PUT', `${of(resource)}/tools`, { ticks }).then(followStepUp),
  tryCall: (resource: string, agent: string, mission: string, tool: string, args: Record<string, unknown>) =>
    request<McpTryResult>('POST', `${of(resource)}/try`, { agent, mission, tool, arguments: args }),
}
