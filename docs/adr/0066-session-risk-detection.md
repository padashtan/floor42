# ADR-0066: Session risk v1: detection, alerts and a quarantine the director applies

Date: 2026-11-09 · Status: Accepted 2026-11-12 (implemented in v1.0.0). One new event type,
`SessionRiskRaised`, approved by the director on 2026-11-06.

## Context

The hook, the broker, the sandbox and token binding enforce permissions outside the model. They do not see misuse
inside the allowed scope: an agent steered by text it read, drifting, or retrying variations after a refusal. The
spec asks for a deterministic evaluation of each session's behaviour, read from the events Floor42 already writes.
The director narrowed the scope for v1.0.0 to detection and alerts. The spec's step-up level is not built in v1.

## Decision (proposed)

1. The score model is pure Java in `floor42-domain` (`SessionRisk`, `RiskReason`, `RiskLevel`). No model call
   anywhere in the risk path: rules are code, deterministic, tested on a table of signal sequences.
2. Signals and weights: `POLICY_DENIED` 1, `COMMAND_DENIED` 1, `OUTSIDE_WORKSPACE` 2, the broker refusals by code
   (`BROKER_PATH_NOT_ALLOWED`, `BROKER_METHOD_NOT_ALLOWED`, `BROKER_RESOURCE_NOT_GRANTED`,
   `BROKER_RESOURCE_UNKNOWN`) 2 each, `INJECTION_MARKER` 1 (and it taints the session), `UNTRUSTED_READ` 0 (taints
   only), `OWN_LEASH` 0 (QUARANTINE at once). `BURST` and `TAINTED_THEN_REFUSED` are derived by the model, never
   given by a caller. Broker codes that are not the agent's doing (identity codes, `NOT_AUTHORIZED`, the network
   codes) raise nothing.
3. Levels: QUARANTINE when `OWN_LEASH`, `BURST` (3 refusals within 5 minutes) or `TAINTED_THEN_REFUSED` is present;
   else ALERT at a summed weight of 2 or more; else NOTICE at 1; else NONE. Levels only go up within a session; a new
   session starts at NONE; the domain refuses a raise that does not go up.
4. Thresholds are named constants in `SessionRisk` and start strict (more alerts), as the director decided. Changing
   one is a code change with its table row, never configuration.
5. Only `Origin.REAL` events feed the watch; simulated and replayed sessions raise nothing.
6. `SessionRiskWatch` subscribes to the office's event batches. Per event it does a constant amount of work. The
   hook's call path does no I/O and no journal write for risk. Journaling `SessionRiskRaised` is forked off the
   subscriber. An exception in the watch is caught and logged and never allows anything: the watch decides nothing.
7. `SessionRiskRaised` carries codes, ids and instants only: never a summary, a path, a command or a body. Rule texts
   never go into a brief, so the agent cannot read them.
8. The director sees a To do row (and the waiting banner) with a button "Propose quarantine". The quarantine is
   proposed, never automatic. On the click, `POST /api/missions/{id}/quarantine` chains existing paths in this order:
   revoke the delegation locally (immediate refusal), suspend the mission (the dispatcher stops relaunching), stop the
   process. The route needs the director, requires a risk of at least ALERT, and is idempotent. Nothing widens a
   permission.
9. Injection markers in brokered answers are a fixed list checked on the body in `McpResource`; the body is not
   journaled.

## Decision D1: documented gap for v1

The director decided that a quarantined mission resumes only with a new approval that grants a new delegation. In IAM
mode no route does that today for a quarantined or suspended mission whose plan is already approved: `StepUpApprovals`
grants a delegation only for a plan awaiting approval, and `Delegations.blocks` answers "delegation revoked". Options
were (a) v1 ships with the existing resume, and in IAM mode the mission stays blocked until the director cancels and
replans; (b) a re-delegation step-up for a suspended mission, which is new security code and needs its own
counter-check. Decided (a), 2026-11-09: it stays a documented known gap for v1 with no route, because the director
holds the scope before v1.0.0 and (b) is a security change of its own. It is listed in docs/iam-revocation.md. Outside
IAM mode, resume works as today. Each accepted quarantine is journaled once as `MissionQuarantined` (steps done, codes
only).

## Consequences

- The director gets a deterministic, explainable alert per session, with the rule codes that fired.
- Nothing is stopped, revoked or suspended without the director's click.
- The cost of the allow path is not measured yet. The plan sets a constant-work budget per event; a measurement is
  still to be made and recorded here.
- The false-positive rate is not measured yet.
- Amends the consequences of ADR-0064 by reference (a "See ADR-0066" line there).

## Known limits

- No step-up and no "false positive" answer in v1. The answer needs its own event and the director's approval.
- Counters live in memory; a daemon restart ends sessions anyway.
- The injection markers are a list; a determined injection avoids it.
- Rule names are visible in the journal, to the director only.
- Not built: tool bypass, obfuscation, runaway and pressure-on-the-human rules.
- A patient agent that stays under the thresholds is not seen.
