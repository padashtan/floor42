// Mirrors of the daemon's journal integrity views (ADR-0079), hand-written until the contract is regenerated.

import type { Origin } from './types.office'

/** check: VERIFIED, INVALID (bad signature), MISMATCH (the chain holds another hash there) or OTHER_KEY (not verifiable). */
export interface CheckpointView {
  sequence: number
  hash: string
  signedAt: string
  kind: 'ADOPTION' | 'PERIODIC' | 'SHUTDOWN'
  keyId: string
  signature: string
  check: 'VERIFIED' | 'INVALID' | 'MISMATCH' | 'OTHER_KEY'
}

/**
 * The journal's hash chain. BROKEN refuses appends (JOURNAL_CHAIN_BROKEN) until the director acknowledges; UNCHECKED
 * means the check could not run and refuses nothing. adoptedRows were chained after the fact: nothing proves them
 * before that.
 */
export interface IntegrityView {
  status: 'OK' | 'ACKNOWLEDGED' | 'BROKEN' | 'UNCHECKED'
  brokenSequence: number | null
  reason:
    | 'HASH_MISMATCH'
    | 'PREV_MISMATCH'
    | 'UNCHAINED_ROW'
    | 'MISSING_ROW'
    | 'CHECKPOINT_SIGNATURE'
    | 'CHECKPOINT_MISMATCH'
    | null
  rows: number
  chainedRows: number
  adoptedRows: number
  pendingRows: number
  acknowledgedBreaks: number
  checkpoints: number
  signaturesVerified: number
  signaturesInvalid: number
  signaturesOtherKey: number
  keyId: string | null
  keyProblem: string | null
  lastCheckpoint: CheckpointView | null
  writesRefused: boolean
  checkedAt: string
  /** REAL: computed on the files of the data folder, never simulated. */
  origin: Origin
}
