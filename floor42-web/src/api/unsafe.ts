// Unsafe mode (ADR-0063): the views of /api/unsafe-mode. Kept apart from types.ts, which is at its size ceiling;
// contract.ts checks these types against openapi.json all the same.

/** GET and PUT /api/unsafe-mode. `sandboxWorks` is null when nothing needs the sandbox, so nothing was measured. */
export interface UnsafeModeView {
  on: boolean
  sandboxWorks: boolean | null
}

/** PUT /api/unsafe-mode. A reason is required to switch on. */
export interface UnsafeModeRequest {
  on: boolean
  reason?: string | null
}
