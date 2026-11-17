# ADR-0082: Trust levels: shadow mode and probation

Date: 2026-11-16 · Status: Proposed (v1.3). One new event type, `AgentTrustChanged`, approved by the director on
2026-11-16 (question 6 of the 1.3.0 questions).

## Context

Every agent on staff gets the mission's permission table as soon as it starts, whatever its record. A new agent, or
one that just caused an incident, acts with the same rights as one whose work the director accepted twenty times. The
roadmap asks for shadow mode (the agent proposes each command, the director runs it) and probation (rights narrow
after an incident and widen after accepted work). They are one mechanism.

## Decision (proposed)

1. `TrustLevel` is `SHADOW`, `PROBATION` or `STANDARD`. It is a cap applied after the permission table, in
   `ActionLog.byPolicy`, by `TrustCap`: the result is never less restrictive than the table's decision (ALLOW, ASK,
   ASK_ALWAYS, DENY). It is never a permission rule: an agent rule outranks the mission's table (D15), so a rule
   written for probation could widen a director's DENY.
2. PROBATION turns an ALLOW on WRITE into ASK, and an ALLOW or ASK on EXECUTE and NETWORK into ASK_ALWAYS. SHADOW
   turns every ALLOW or ASK on WRITE, EXECUTE and NETWORK into a denial with `DenialReason.POLICY` and the basis
   `DecisionBasis.trustShadow()`. READ, FLOOR42 and OTHER, and any DENY, stay the table's.
3. A shadow denial is a proposal, not a refusal of the agent's doing: `SessionRiskWatch` skips a denial with that
   basis, so proposals raise no risk. The text the agent reads is the fixed `DecisionBasis.SHADOW_AGENT_TEXT`: no rule
   name, no level history (ADR-0066 point 7).
4. An incident (a call that ran without authorization), a session risk raised to ALERT or above, and a quarantine each
   narrow the agent's level by one, in the command that records them: one `AgentTrustChanged` by Floor42 with the
   cause and the causing event's id. Only REAL events count; a simulation lowers nothing. Nothing goes below SHADOW.
5. After `Probation.ACCEPTED_FOR_RAISE` (5, a named constant: changing it is a code change with its row in
   `ProbationTest`) patches accepted by the director since the last change, with no incident, the agent shows
   `raiseProposed` and the director sees a To do row. Nothing widens by itself. A peer review by an agent is not the
   director's acceptance and does not count.
6. The director raises a level only with proof: the domain refuses `raiseTrust` without it
   (`TRUST_RAISE_NEEDS_STEP_UP`). The proof is the assured approvals' mechanism (ADR-0081: a fresh strong sign-in and,
   with four eyes on, a second signature); this node exposes it as the `TrustRaiseStepUp` interface and tests with a
   fake. The default, `LocalTrustRaiseStepUp`, answers true without IAM mode (one local director, no stronger proof
   exists) and false in IAM mode until the real mechanism replaces it. Lowering, including putting an agent in shadow by
   hand, is a plain click: restricting never needs proof.
7. Staff already hired at the upgrade stay STANDARD (a journal without `AgentTrustChanged` replays to STANDARD). A real
   hire afterwards records `AgentTrustChanged(STANDARD, PROBATION, HIRE)` right after `AgentHired`; a simulated hire
   stays STANDARD, since a simulation runs no real call to cap.
8. The Proposals panel (Work menu) lists a mission's shadow proposals, master and detail, with Copy and "Put in my
   terminal". The second prepares `! <command>` in the director's terminal through its own prefill path, a real click
   only, printable ASCII only, never Enter. The result goes back to the agent in a directive the director types.

## Rejected options, with their cost

| Option | Cost | Why not |
|---|---|---|
| Trust as permission rules written by Floor42 | no new event | an agent rule outranks the table: it can widen a DENY |
| Automatic widening after N reviews | none | widening rights without a human is what this release exists to stop |
| A new `DenialReason.SHADOW` | clearer filter | a changed `ActionDenied` and a downgrade hole, for what a basis already says |
| Shadow as "ask every time" | nothing to build | the director would approve and the agent would run it: not shadow |
| Executing the proposal from the panel | convenience | the actor would be Floor42 running agent text; the terminal keeps the director's hands on it |
| Counting agent peer reviews | more signal | a model's review is not the director's acceptance |

## Consequences

- Rollback: a 1.2.0 binary sets `AgentTrustChanged` rows aside (`NEWER_VERSION`, ADR-0042) and applies the director's
  table without the cap. It falls back to what the director approved, never beyond it.
- Limits: the count is per agent across missions, and a one-line fix weighs as much as a large patch. A proposal of a
  Write or an Edit shows its masked summary only. A drop to SHADOW does not stop a Bash process already running (the
  quarantine of ADR-0066 does). "Put in my terminal" opens in the last terminal's folder, not the agent's worktree.
