# ADR-0053: A reservation cites a criterion and says what to change

Date: 2026-11-03 · Status: Accepted (the director approved the new event type `ReviewItemsRecorded`; its point of no return is the release that ships it)

## Context
Measured in the director's real journal: 27 of the 33 validated reviews carry reservations (82%). A reservation no longer
tells the director anything. Until now the reviewer's brief listed the ticket's acceptance criteria as bullets and
offered four verdicts; `ReviewRunner.verdictOf` read the last `VERDICT:` line and `TicketReviewed` kept it as written,
with the whole text as findings. Nothing tied a reservation to a criterion, and nothing checked one.

## Decision
- The reviewer's brief numbers the criteria (`C1`, `C2`...) and asks for one line per reservation,
  `RESERVATION C<n>: <what falls short> | CHANGE: <what to change>`, and `NOTE: <remark>` for anything else.
- `ReviewClaims` (server) only reads those lines. The domain decides: `ReviewCalibration.classify` keeps a reservation
  only when it cites a criterion the ticket has and says what to change; any other item is a `ReviewNote` with a code
  for why (`NOTE`, `NO_CRITERION`, `UNKNOWN_CRITERION`, `NO_CHANGE`). The criterion's text is copied into the
  reservation, so a later `TicketEdited` does not change what it cited.
- Floor42 never rewrites the reviewer's verdict (the director's decision, 2026-11-03). `TicketReviewed` holds the
  verdict as declared, always; calibration only classifies the items. Why: a parser that reads a free text cannot
  be trusted to overrule the reviewer. A partial read would downgrade a real "with reservations", a stray `CHANGE:`
  line would upgrade a "validated", and the record would then say something the reviewer never said.
- The calibrated signal lives in the counters, not in the verdict: `Ticket.Review.reservationsStand` is true for a
  validating review with at least one kept reservation, or whose declared reservations could not be read
  (`UNPARSED`, nothing says they do not stand); for a review journaled before this release, its verdict as recorded.
  The end-of-mission report counts "with reservations" from it, and warns `REVIEW_VALIDATED_WITH_RESERVATIONS` only
  then; a validation whose items are all notes counts as validated. Everywhere else the declared verdict is shown
  as is.
- When the reviewer declared VALIDATED_WITH_RESERVATIONS and Floor42 read no item at all, one `UNPARSED` note says so,
  and the screen tells the director to read the findings rather than showing "none".
- New event `ReviewItemsRecorded(ticket, reviewer, declared, reservations, notes)`, emitted in the same command right
  after `TicketReviewed` (which holds the declared verdict), with the reviewer as actor and the review's origin. Being
  one command, there is no state where the review is journaled without its items. Replay attaches it to the ticket's
  last review, which must be the same reviewer's and not already sorted; anything else stops the replay.
- `TicketReviewed` is unchanged. A review journaled before this release has no `ReviewItemsRecorded`: `Ticket.Review.items`
  is null and its verdict, reservations included, keeps the meaning it had. Simulated missions and the scenarios keep the
  four-argument `review`, which records no items.
- The screens (board, deliverables) show reservations and notes apart (`ReviewItems.vue`) and show the declared verdict; the codes are worded by the catalogues, never by the server.

## Options not taken
- Recording the verdict that follows from what was kept (this ADR's first version): the recheck showed a partial read
  downgrading a real reservation and a far-away `CHANGE:` line upgrading a validation. The director decided against it.
- A trailing field on `TicketReviewed`: changes a serialized form that every journal holds, for a value most old rows
  cannot have.
- Prompt text only: the 82% came from prompt text that already said what a reservation is for.
- Refusing a review whose reservations cite no criterion: the session's work would be lost for a format slip; a note
  keeps what the reviewer said without letting it count.

## Reopen when
- Reviews often cite no criterion because tickets come without any (`- (none stated)`): then the planner's `DONE WHEN`
  needs work, not the reviewer.
- The share of reservations stays high once the items are sorted: the criteria themselves may be too broad.
