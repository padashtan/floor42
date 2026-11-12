package dev.floor42.domain.event;

/**
 * Everything that can happen in the company. Events are facts in the past tense, immutable, and the only way state
 * changes: the current state is the result of replaying them in order.
 *
 * Each area, one file of this package, holds its event types as nested records: the simple names are what the
 * journal stores (ADR-0009, D19).
 *
 * <p>Nullable fields are documented on each record. Adding a type here requires adding it to the journal codec test.
 */
public sealed interface DomainEvent
        permits RosterEvent,
                MissionEvent,
                TicketEvent,
                ActionEvent,
                DeskEvent,
                SessionEvent,
                VerificationEvent,
                WorkbenchEvent,
                CommitteeEvent,
                CommitteeSeatEvent,
                StaffingEvent,
                RetroEvent,
                DeliveryEvent,
                PreferenceEvent,
                NotebookEvent,
                TerminalEvent,
                RecentProjectEvent,
                MeetingEvent,
                WorkflowEvent {}
