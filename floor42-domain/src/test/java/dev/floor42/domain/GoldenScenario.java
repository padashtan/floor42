package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.golden.GoldenFile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs commands one by one on a company and writes down, for each, the events it emitted or the refusal it met. The
 * clock is fixed and moves one minute per command, so the record depends on the code only.
 */
final class GoldenScenario {

    final Company company = new Company();
    final List<EventEnvelope> journal = new ArrayList<>();
    final StringBuilder record = new StringBuilder();
    private Instant now = Instant.parse("2026-10-20T08:00:00Z");
    private int commands;

    CommandContext director(Origin origin) {
        return ctx(Actor.director(), origin, null);
    }

    CommandContext agent(AgentId id, Origin origin) {
        return ctx(Actor.agent(id), origin, null);
    }

    CommandContext system(Origin origin) {
        return ctx(Actor.system(), origin, null);
    }

    /** A command caused by a tool call of the runtime: its events carry the call's id. */
    CommandContext caused(Actor actor, Origin origin, String causation) {
        return ctx(actor, origin, causation);
    }

    private CommandContext ctx(Actor actor, Origin origin, String causation) {
        now = now.plusSeconds(60);
        return new CommandContext(actor, origin, now, causation);
    }

    /** Runs one command and records what it did; a refusal is recorded too, and the scenario goes on. */
    List<EventEnvelope> run(String label, Supplier<List<EventEnvelope>> command) {
        commands++;
        record.append(">>> ").append(commands).append(' ').append(label).append('\n');
        try {
            List<EventEnvelope> events = command.get();
            record.append(GoldenFile.events(events, journal.size()));
            if (events.isEmpty()) record.append("  (nothing recorded)\n");
            journal.addAll(events);
            return events;
        } catch (DomainException e) {
            record.append("  refused ")
                    .append(e.code())
                    .append(": ")
                    .append(e.getMessage())
                    .append('\n');
            return List.of();
        } catch (RuntimeException e) {
            record.append("  threw ").append(e.getClass().getName()).append('\n');
            return List.of();
        }
    }

    /** Runs a check that records nothing: passes, or is refused. */
    void check(String label, Runnable check) {
        run(label, () -> {
            check.run();
            return List.of();
        });
    }

    /** Runs a query that answers a value: the answer is recorded. */
    void ask(String label, Supplier<?> query) {
        commands++;
        record.append("??? ").append(commands).append(' ').append(label).append(" = ");
        try {
            record.append(dev.floor42.domain.golden.Canonical.of(query.get())).append('\n');
        } catch (DomainException e) {
            record.append("refused ")
                    .append(e.code())
                    .append(": ")
                    .append(e.getMessage())
                    .append('\n');
        }
    }

    QuestionId openQuestion(MissionId mission, QuestionKind kind) {
        return company.desk().of(mission).stream()
                .filter(q -> q.kind() == kind && q.status() == QuestionStatus.OPEN)
                .reduce((a, b) -> b)
                .map(Question::id)
                .orElse(new QuestionId("Q-none"));
    }

    QuestionId questionFor(MissionId mission, ActionId action) {
        return company.desk().of(mission).stream()
                .filter(q -> action.equals(q.action()))
                .reduce((a, b) -> b)
                .map(Question::id)
                .orElse(new QuestionId("Q-none"));
    }

    QuestionId questionAbout(MissionId mission, String subject) {
        return company.desk().of(mission).stream()
                .filter(q -> q.subject().equals(subject))
                .reduce((a, b) -> b)
                .map(Question::id)
                .orElse(new QuestionId("Q-none"));
    }

    TicketId lastTicket(MissionId mission) {
        return company.tickets().of(mission).getLast().id();
    }
}
