# ADR-0078: The 9 o'clock standup, built from the journal

Date: 2026-11-16 · Status: Proposed

## Context

The director opens Floor42 in the morning and wants, per agent, what was done last time, what is on its plate now and
what stops it, without reading the event log. "While you were away" (ADR-0033 family, `AwaySummary`) answers "what
happened since I last looked", for the whole company, and is kept apart: it follows an acknowledged sequence, not a
day, and says nothing per agent.

The rules stay: nothing estimated or invented is shown as a fact, no model call, mood and persona never change work
and never appear in it.

## Decision

`GET /api/standup` (director only, read only, journals nothing, `Cache-Control: no-store`) returns the standup of the
company, one entry per agent on the staff:

- **Yesterday**: the work of the last local day before today that holds a real event. It is a day with activity, not
  strictly the calendar day before, so that Monday shows Friday; the view names it (`previousDay`). Per agent: tickets
  the agent submitted for review, tickets completed that were assigned to it, sessions run and sessions failed.
- **Today**: what is on the plate now: tickets assigned to the agent in progress, in review, or queued; sessions it
  ran since 00:00 local.
- **Blocked**: the agent's assignments suspended; its blocking questions still open (asked by it or on its ticket);
  its queued tickets whose dependencies are not done.

It is a pure function of the real events (`StandupComposer.compose(events, now, zone)`), no I/O, no state. Simulated
events are left out. The lines carry a `code` (the UI translates, ADR-0021), the ticket or question id, and the
ticket title or question subject cut to 120 characters, shown by the page as plain text (agent text is data). A day
without anything for an agent returns empty lists, and the page says so; it never fills a gap.

The day is computed in the browser's zone (`zone` query parameter, an IANA id, validated: an unknown id is a 400,
the daemon's zone when absent).

**The panel** (`Standup`, Work menu, action `standup`, no shortcut key): master-detail, the agents on the left with
a count of what blocks, the selected agent's three columns on the right (no scrolling to read one agent). It opens by
itself once a day: on the first load of the app after 09:00 local, if nothing was dismissed that day. The date of the
last dismissal is kept in this browser only (`localStorage`, may be absent or blocked: then it opens at each load after
09:00, which is accepted). Nothing is journaled, not the opening and not the dismissal.

## Rejected

- **A model writing the standup.** A call that costs, may invent, and breaks "built from the journal only".
- **A new journal event** ("StandupShown"): adds a row for a screen convenience; the browser can keep one date.
- **Strict calendar yesterday.** Empty on Mondays, where the director most wants it.
- **Server-side "shown once".** The daemon does not know which browser looked; a second screen would lose it.
- **Reusing `AwaySummary`.** Sequence-based and company-wide; bending it would blur two questions.

## Limits

- The journal is read whole (`readAll`), as the proof dossier does; a very long journal makes it slow. Upgrade to an
  indexed read from the start of the previous day when it shows.
- A session without an end (a crash, node B) counts as started today and never as ended; nothing is inferred.
- "Blocked" lists what the journal shows. A stuck agent with no open question and no unmet dependency is not shown
  here; the stall detection of the dispatcher stays the place for that.
- Names come from the hiring event; an agent hired before names were recorded shows its id.
