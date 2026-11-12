package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director's objections at acceptance become work in the same mission (ADR-0060). */
@ExtendWith(GoldenFixtureExtension.class)
class ObjectionsTest {

    final Fixture f = new Fixture();

    private TicketId done(MissionId m) {
        TicketId t = f.firstTicket(m);
        finish(t);
        return t;
    }

    private void finish(TicketId t) {
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.VALIDATED, "ok"));
    }

    private String code(Runnable r) {
        return ((DomainException) org.junit.jupiter.api.Assertions.assertThrows(DomainException.class, r::run)).code();
    }

    @Test
    void anObjectionToADoneTicketReopensItWithTheTextAsADirective() {
        MissionId m = f.runningMission();
        TicketId t = done(m);

        List<EventEnvelope> events = f.record(
                f.company.missions().object(f.director(), m, List.of(new Objection(t, "  Cover the expiry  "))));

        assertThat(events)
                .extracting(e -> e.event().getClass().getSimpleName())
                .containsExactly(DirectiveGiven.class.getSimpleName(), TicketReopened.class.getSimpleName());
        assertThat(events).extracting(EventEnvelope::causationId).containsOnly("objections-1");
        Ticket reopened = f.company.tickets().find(t).orElseThrow();
        assertThat(reopened.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(reopened.directives()).containsExactly("Cover the expiry");
        assertThat(f.company.missions().objectionRounds(m))
                .containsExactly(new ObjectionRound(1, List.of(t), List.of()));
    }

    @Test
    void newWorkIsAnApprovedWorkTicketWithTheObjectionAsItsCriterion() {
        MissionId m = f.runningMission();
        done(m);
        f.record(f.company.missions().enableTicketValidation(f.director(), m));

        List<EventEnvelope> events = f.record(f.company
                .missions()
                .object(f.director(), m, List.of(new Objection(null, "Add a French README\nplease"))));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().causationId()).isEqualTo("objections-1");
        TicketId created = ((TicketCreated) events.getFirst().event()).ticket();
        Ticket t = f.company.tickets().find(created).orElseThrow();
        assertThat(t.kind()).isEqualTo(TicketKind.WORK);
        assertThat(t.status()).isEqualTo(TicketStatus.TODO);
        assertThat(t.title()).isEqualTo("Add a French README");
        assertThat(t.acceptanceCriteria())
                .containsExactly("Done as the director's objection asks: Add a French README\nplease");
        assertThat(f.company.missions().objectionRounds(m))
                .containsExactly(new ObjectionRound(1, List.of(), List.of(created)));
    }

    @Test
    void oneRoundIsOneCommandAndOneCauseThenTheNextRoundTakesTheNextNumber() {
        MissionId m = f.runningMission();
        TicketId t = done(m);

        List<EventEnvelope> first = f.record(f.company
                .missions()
                .object(
                        f.director(),
                        m,
                        List.of(new Objection(t, "one"), new Objection(null, "two"), new Objection(t, "three"))));

        assertThat(first).extracting(EventEnvelope::causationId).containsOnly("objections-1");
        assertThat(first).hasSize(2 + 1 + 1);
        assertThat(code(() -> f.company.missions().object(f.director(), m, List.of(new Objection(null, "x")))))
                .isEqualTo("NOT_AT_ACCEPTANCE");

        finish(t);
        TicketId created = f.company.tickets().of(m).getLast().id();
        f.record(f.company.tickets().cancel(f.director(), created, "not needed"));
        List<EventEnvelope> second =
                f.record(f.company.missions().object(f.director(), m, List.of(new Objection(t, "again"))));

        assertThat(second).extracting(EventEnvelope::causationId).containsOnly("objections-2");
        assertThat(f.company.missions().objectionRounds(m))
                .extracting(ObjectionRound::number)
                .containsExactly(1, 2);
        assertThat(f.company.missions().objectionRounds(m).getFirst().targeted())
                .containsExactly(t, t);
    }

    @Test
    void refusalsRecordNothing() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        List<Objection> ok = List.of(new Objection(t, "x"));

        assertThat(code(() -> f.company.missions().object(f.director(), m, ok))).isEqualTo("NOT_AT_ACCEPTANCE");
        finish(t);
        int before = f.journal.size();
        long version = f.company.version();

        assertThat(code(() -> f.company.missions().object(f.agent(CHRIS), m, ok)))
                .isEqualTo("DIRECTOR_ONLY");
        assertThat(code(() -> f.company.missions().object(f.director(), m, List.of())))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(code(() -> f.company.missions().object(f.director(), m, null)))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(code(() ->
                        f.company.missions().object(f.director(), m, java.util.Collections.nCopies(11, ok.getFirst()))))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(code(() -> f.company.missions().object(f.director(), m, List.of(new Objection(t, " ")))))
                .startsWith("MISSING_");
        assertThat(code(() ->
                        f.company.missions().object(f.director(), m, List.of(new Objection(t, "x".repeat(4_001))))))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(code(() -> f.company
                        .missions()
                        .object(f.director(), m, List.of(new Objection(new TicketId("T-99"), "x")))))
                .isEqualTo("UNKNOWN_TICKET");
        // a valid first objection and an invalid second one: nothing of the first is recorded
        assertThat(code(() -> f.company
                        .missions()
                        .object(f.director(), m, List.of(new Objection(t, "x"), new Objection(t, " ")))))
                .startsWith("MISSING_");

        assertThat(f.journal).hasSize(before);
        assertThat(f.company.version()).isEqualTo(version);
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
    }

    @Test
    void aClosedMissionAndATicketThatIsNotDoneAreRefused() {
        MissionId m = f.runningMission();
        TicketId t = done(m);
        f.record(f.company.missions().object(f.director(), m, List.of(new Objection(null, "more"))));
        TicketId pending = f.company.tickets().of(m).getLast().id();
        f.record(f.company.tickets().cancel(f.director(), pending, "dropped"));

        assertThat(code(() -> f.company.missions().object(f.director(), m, List.of(new Objection(pending, "x")))))
                .isEqualTo("INVALID_TRANSITION");

        f.record(f.company.missions().complete(f.director(), m, "delivered"));
        assertThat(code(() -> f.company.missions().object(f.director(), m, List.of(new Objection(t, "x")))))
                .isEqualTo("NOT_AT_ACCEPTANCE");
    }

    @Test
    void newWorkGetsTheMissionsTableNotTheRulesOfTheTicketObjectedTo() {
        MissionId m = f.runningMission();
        finish(f.firstTicket(m));
        TicketId target = f.ticket(m, "Second");
        f.record(f.company.tickets().assign(f.director(), target, CHRIS));
        f.record(f.company.tickets().start(f.agent(CHRIS), target));
        f.record(f.company.missions().setToolPermission(f.director(), m, "Write", ActionCategory.WRITE, Decision.DENY));
        f.record(f.company
                .missions()
                .addPermission(
                        f.director(),
                        m,
                        PermissionRule.forTicket(target, "Write", ActionCategory.WRITE, Decision.ASK)));
        finish(target);

        List<EventEnvelope> events = f.record(f.company
                .missions()
                .object(f.director(), m, List.of(new Objection(target, "fix"), new Objection(null, "more"))));

        TicketId added = ((TicketCreated) events.getLast().event()).ticket();
        PermissionPolicy policy = f.company.missions().find(m).orElseThrow().policy();
        assertThat(policy.evaluate(CHRIS, target, ActionCategory.WRITE, "Write", "a.txt")
                        .decision())
                .isEqualTo(Decision.ASK);
        assertThat(policy.evaluate(CHRIS, added, ActionCategory.WRITE, "Write", "a.txt")
                        .decision())
                .as("the rule of the ticket objected to is not the new ticket's")
                .isEqualTo(Decision.DENY);
    }
}
