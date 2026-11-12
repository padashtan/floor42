# ADR-0009: Event-sourced company over an append-only SQLite journal

Date: 2026-10-20 · Status: Accepted

## Context
The spec requires missions and decisions to survive a restart, every important event to be traceable (who, under which authorization, with which result), and invariants to be enforced by code. ADR-0005 makes provenance mandatory on every event.

Options compared:
- **Relational tables updated in place (ORM):** familiar, but history and provenance need a separate audit trail that can drift from the state.
- **Full event sourcing with per-aggregate streams, snapshots and async projections:** maximum flexibility, far too much machinery for a single-user local application.
- **One event stream, one in-memory projection:** chosen.

## Decision
- `Company` (domain) is the single consistency boundary. Commands check every rule first, then record events; state changes only by applying events. `Company.replay(journal)` rebuilds the exact same state.
- The journal is one SQLite table, `event_journal`, written atomically per command and **append-only, enforced by database triggers**.
- `OfficeService` serializes commands. If writing fails, it rebuilds the company from the journal, so the in-memory state never shows something that was not stored.
- The event JSON format belongs to the journal (`EventCodec`), not to the REST layer; types are discovered from the sealed `DomainEvent` hierarchy, and a test round-trips every one of them.
- Schema changes go through Flyway migrations.

## Consequences
- Restart replays the whole journal. Fine for thousands of events; measure before it matters (signal below).
- Changing an event's shape later requires an upcaster or a new event type: events already stored are never rewritten.
- Concurrency is trivial (one lock); one company per data directory.

## Reopen if
Startup replay exceeds one second, or several processes need to write to the same company.

## Amendment 2026-10-31: evolving an event, and stable meanings

- **Additive fields only.** An event gains a field as a new nullable record component at the end; the decoder leaves it null in a row written before (Jackson, missing property). Code reads null as "written before this field existed" and applies the rule of that time, never a guess. A field is never renamed, retyped or removed: that needs a new event type or an upcaster.
- **`QuestionAsked.optionCodes`** (1.6.0): the stable code (`OptionCode`) of each option Floor42 offers, by position, null where the option is not Floor42's own (an agent's or the analyst's options, the committee ruling, ALLOW/DENY). Code compares codes, never the English label, which stays the text shown and the answer journaled. A question journaled before has no list: its options are read by their label, current or former (`OptionCode.legacyLabels`). An agent's option is never taken for Floor42's, whatever its words. The API also accepts an option's code as the answer.
- **`TicketCreated.kind`** (1.6.0): `WORK` or `PLANNING`, recorded at creation; the planner's ticket is no longer recognised by its title ("Plan: "), which the director may rewrite. A ticket journaled before has no kind: it is read from the title it was created with, the rule of that time, for those events only.
- **Commands check before they emit.** Emitting applies the event at once; a command refused after its first event would leave memory ahead of the journal. Every check runs first; `OfficeService.execute` also rebuilds from the journal when a command fails after applying events, and fails loudly in tests (`floor42.office.fail-on-rollback`).
