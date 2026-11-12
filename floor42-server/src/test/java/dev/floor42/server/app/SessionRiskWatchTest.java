package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.RiskLevel;
import dev.floor42.domain.RiskRaise;
import dev.floor42.domain.RiskReason;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionFailed;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The watch turns hook and broker decisions into raises, with constant work per event and no journal write inline. */
class SessionRiskWatchTest {

    static final MissionId M = new MissionId("M-1");
    static final TicketId T = new TicketId("T-1");
    static final AgentId ADA = new AgentId("ada");
    static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    /** Records the raises instead of journaling them: the journaling itself is a forked call of the facade (T2). */
    @jakarta.enterprise.inject.Vetoed
    static final class Spy extends SessionRiskWatch {
        final List<RiskRaise> raises = new ArrayList<>();

        Spy() {
            super(null, new MissionScheduler(), java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        }

        @Override
        void journal(String session, RiskRaise r) {
            raises.add(r);
        }
    }

    private int n;

    private EventEnvelope env(Origin origin, int sec, DomainEvent e) {
        return new EventEnvelope("e" + n++, T0.plusSeconds(sec), origin, Actor.system(), M, null, null, e);
    }

    private EventEnvelope real(int sec, DomainEvent e) {
        return env(Origin.REAL, sec, e);
    }

    private EventEnvelope start() {
        return real(0, new AgentSessionStarted("s1", M, T, ADA, "m", null, null));
    }

    private EventEnvelope request(int sec, String id, String tool, String summary) {
        return real(sec, new ActionRequested(new ActionId(id), M, T, ADA, ActionCategory.READ, tool, summary));
    }

    private EventEnvelope deny(int sec, String id, DenialReason why) {
        return real(sec, new ActionDenied(new ActionId(id), why, "x"));
    }

    private Spy spy(EventEnvelope... events) {
        Spy s = new Spy();
        s.react(List.of(events));
        return s;
    }

    @Test
    void threeOutsidePathsRaiseAlertThenQuarantine() {
        Spy s = spy(
                start(),
                request(0, "a1", "Read", "/x"),
                deny(0, "a1", DenialReason.OUTSIDE_WORKSPACE),
                request(10, "a2", "Read", "/y"),
                deny(10, "a2", DenialReason.OUTSIDE_WORKSPACE),
                request(20, "a3", "Read", "/z"),
                deny(20, "a3", DenialReason.OUTSIDE_WORKSPACE));
        assertThat(s.raises).extracting(RiskRaise::level).containsExactly(RiskLevel.ALERT, RiskLevel.QUARANTINE);
        RiskRaise last = s.raises.getLast();
        assertThat(last.reasons()).containsExactly(RiskReason.OUTSIDE_WORKSPACE, RiskReason.BURST);
        assertThat(last.actions()).containsExactly(new ActionId("a1"), new ActionId("a2"), new ActionId("a3"));
    }

    @Test
    void aDeniedShellCommandCountsAsACommand() {
        Spy s = spy(start(), request(0, "a1", "Bash", "rm -rf x"), deny(0, "a1", DenialReason.POLICY));
        assertThat(s.raises).hasSize(1);
        assertThat(s.raises.getFirst().level()).isEqualTo(RiskLevel.NOTICE);
        assertThat(s.raises.getFirst().reasons()).containsExactly(RiskReason.COMMAND_DENIED);
    }

    @Test
    void theDirectorsNoIsNotARisk() {
        Spy s = spy(
                start(),
                request(0, "a1", "Read", "a"),
                deny(0, "a1", DenialReason.DIRECTOR),
                request(1, "a2", "Read", "b"),
                deny(1, "a2", DenialReason.DIRECTOR),
                request(2, "a3", "Read", "c"),
                deny(2, "a3", DenialReason.DIRECTOR));
        assertThat(s.raises).isEmpty();
        assertThat(s.inFlight()).isZero();
    }

    @Test
    void aBrokerRefusalByCodeIsStrong() {
        Spy s = spy(
                start(),
                request(0, "a1", "call_resource", "GET /admin/users"),
                real(1, new ActionFailed(new ActionId("a1"), "PATH_NOT_ALLOWED: /admin/users is outside")),
                request(2, "a2", "call_resource", "GET /x"),
                real(3, new ActionFailed(new ActionId("a2"), "IDENTITY_UNAVAILABLE: no token")));
        assertThat(s.raises).hasSize(1);
        assertThat(s.raises.getFirst().level()).isEqualTo(RiskLevel.ALERT);
        assertThat(s.raises.getFirst().reasons()).containsExactly(RiskReason.BROKER_PATH_NOT_ALLOWED);
    }

    @Test
    void aReadOfUntrustedDataThenARefusalProposesQuarantine() {
        Spy s = spy(
                start(),
                request(0, "a1", "call_resource", "GET /pages/1"),
                real(1, new ActionExecuted(new ActionId("a1"), "ok", true)),
                request(2, "a2", "Read", "x"),
                deny(2, "a2", DenialReason.POLICY));
        assertThat(s.raises).hasSize(1);
        assertThat(s.raises.getFirst().level()).isEqualTo(RiskLevel.QUARANTINE);
        assertThat(s.raises.getFirst().reasons())
                .containsExactly(RiskReason.UNTRUSTED_READ, RiskReason.POLICY_DENIED, RiskReason.TAINTED_THEN_REFUSED);
    }

    @Test
    void ownLeashProposesQuarantineAtTheRequest() {
        Spy s = spy(start(), request(0, "a1", "Read", ".claude/settings.json"));
        assertThat(s.raises).extracting(RiskRaise::level).containsExactly(RiskLevel.QUARANTINE);
    }

    @Test
    void anAllowedCallLeavesNothingBehind() {
        Spy s = new Spy();
        s.react(List.of(start()));
        for (int i = 0; i < 10_000; i++) {
            String id = "r" + i;
            s.react(List.of(
                    request(i, id, "Read", "src/A.java"),
                    real(i, new ActionAuthorized(new ActionId(id), "policy")),
                    real(i, new ActionExecuted(new ActionId(id), "ok", true))));
        }
        assertThat(s.raises).isEmpty();
        assertThat(s.inFlight()).isZero();
    }

    @Test
    void simulatedAndEndedSessionsRaiseNothing() {
        Spy s = spy(
                env(Origin.SIMULATED, 0, new AgentSessionStarted("s2", M, T, ADA, "m", null, null)),
                request(0, "a1", "Read", ".claude/settings.json"));
        assertThat(s.raises).isEmpty();
        Spy ended = spy(
                start(),
                real(1, new AgentSessionEnded("s1", false, "done", null, null, null, null, null)),
                request(2, "a1", "Read", ".claude/settings.json"));
        assertThat(ended.raises).isEmpty();
    }

    @Test
    void aFailingRuleDoesNotBreakTheBatch() {
        Spy s = spy(start(), request(0, "a0", "Read", null), request(1, "a1", "Read", ".env"));
        assertThat(s.raises).extracting(RiskRaise::level).containsExactly(RiskLevel.QUARANTINE);
    }

    /** No spy: a real office, the real fork, and the raise read back from the session the journal rebuilt. */
    @Test
    void aRaiseReachesTheJournalThroughTheRealFork() throws Exception {
        var journal = new OfficeServiceTest.MemoryJournal();
        var office = new OfficeService(journal, java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        MissionScheduler scheduler = new MissionScheduler();
        SessionRiskWatch watch =
                new SessionRiskWatch(office, scheduler, java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        office.subscribe(watch::react);
        var system = office.context(Actor.system(), Origin.REAL);
        var director = office.context(Actor.director(), Origin.REAL);
        var ada = new AgentId("ada");
        office.execute(c -> c.staff().hire(director, ada, "ada", "role", "anthropic", "m", "medium", List.of()));
        MissionId m =
                (MissionId) ((dev.floor42.domain.event.MissionEvent.MissionCreated) office.execute(c -> c.missions()
                                        .create(
                                                director,
                                                "R",
                                                "o",
                                                "C:/repo",
                                                dev.floor42.domain.workflow.Workflow.BUILT_IN))
                                .getFirst()
                                .event())
                        .mission();
        TicketId t = ((dev.floor42.domain.event.TicketEvent.TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        office.context(Actor.agent(ada), Origin.REAL),
                                        m,
                                        "W",
                                        "",
                                        dev.floor42.domain.Priority.NORMAL,
                                        List.of(),
                                        List.of("done")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director, t, ada));
        office.execute(c -> c.missions().submitPlan(office.context(Actor.agent(ada), Origin.REAL), m));
        office.execute(c -> c.verification().setBudget(director, m, null));
        office.execute(c -> c.missions().approvePlan(director, m));
        office.execute(c -> c.tickets().start(office.context(Actor.agent(ada), Origin.REAL), t));
        office.execute(c -> c.sessions().start(system, "s1", t, ada, "m", "2.1"));

        watch.react(List.of(
                real(0, new ActionRequested(new ActionId("a1"), m, t, ada, ActionCategory.READ, "Read", ".env"))));
        SchedulerQuiet.await(scheduler);

        var risk = office.query(c -> c.sessions().find("s1").orElseThrow().risk());
        assertThat(risk).isPresent();
        assertThat(risk.get().level()).isEqualTo(RiskLevel.QUARANTINE);
        assertThat(risk.get().reasons()).containsExactly(RiskReason.OWN_LEASH);
    }
}
