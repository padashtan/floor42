package dev.floor42.server.app.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.MissionEvent.PermissionRuleAdded;
import dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn;
import dev.floor42.domain.event.SessionEvent.MissionQuarantined;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.server.app.delivery.OversightReport.Line;
import dev.floor42.server.security.ReplaySanitizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The oversight report composed from hand-built envelopes: no Quarkus, no git, no journal file. */
class OversightComposerTest {

    private static final MissionId M = new MissionId("M-1");
    private static final TicketId T = new TicketId("T-1");
    private static final AgentId ADA = new AgentId("ada");
    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");

    private static EventEnvelope env(int n, Actor actor, MissionId mission, String cause, DomainEvent e) {
        return new EventEnvelope("e" + n, T0.plusSeconds(n), Origin.REAL, actor, mission, null, cause, e);
    }

    private static EventEnvelope director(int n, DomainEvent e) {
        return env(n, Actor.director(), M, null, e);
    }

    private static EventEnvelope system(int n, DomainEvent e) {
        return env(n, Actor.system(), M, null, e);
    }

    private static ActionRequested asked(String id, String tool, String summary) {
        return new ActionRequested(new ActionId(id), M, T, ADA, ActionCategory.NETWORK, tool, summary);
    }

    private static Mission mission() {
        EventEnvelope created = env(0, Actor.director(), M, null, new MissionCreated(M, "Title", "Objective", null));
        return Company.replay(List.of(created)).missions().find(M).orElseThrow();
    }

    private static OversightReport compose(List<EventEnvelope> events) {
        return OversightComposer.compose(
                mission(), events, new ReplaySanitizer("tok-secret-value", Map.of()), "1.3.0", T0.plusSeconds(3600));
    }

    private static QuestionAsked approvalFor(String action) {
        return new QuestionAsked(
                new QuestionId("Q-1"),
                M,
                QuestionKind.ACTION_APPROVAL,
                null,
                "May ada run curl?",
                null,
                List.of("Yes", "No"),
                null,
                Criticality.MEDIUM,
                true,
                T,
                new ActionId(action),
                null,
                null);
    }

    private static final List<EventEnvelope> JOURNAL = List.of(
            director(
                    1,
                    new PermissionRuleAdded(
                            M, new PermissionRule(null, ActionCategory.NETWORK, "curl", null, Decision.ASK))),
            director(2, new MissionPlanApproved(M)),
            system(3, asked("a1", "curl", "curl https://example.org")),
            system(4, approvalFor("a1")),
            director(5, new QuestionAnswered(new QuestionId("Q-1"), "Yes")),
            system(6, new ActionAuthorized(new ActionId("a1"), "DIRECTOR_APPROVAL")),
            system(7, new ActionExecuted(new ActionId("a1"), "ok", true)),
            system(8, asked("a2", "Bash", "rm -rf build")),
            system(9, new ActionDenied(new ActionId("a2"), DenialReason.POLICY, "no")),
            system(10, asked("a3", "Bash", "git push")),
            director(11, new ActionDenied(new ActionId("a3"), DenialReason.DIRECTOR, "no")),
            system(12, asked("a4", "Bash", "ls")));

    private static List<String> kinds(OversightReport.Section s) {
        return s.items().stream().map(Line::kind).toList();
    }

    @Test
    void theFourSectionsHoldWhatTheJournalHolds() {
        OversightReport r = compose(JOURNAL);
        assertThat(kinds(r.supervision())).containsExactly("PLAN_APPROVED", "APPROVAL_ANSWERED");
        assertThat(kinds(r.capabilities())).containsExactly("PERMISSION_RULE");
        assertThat(kinds(r.actions())).containsExactly("ACTION", "ACTION", "ACTION", "ACTION");
        assertThat(kinds(r.overrides())).containsExactly("DIRECTOR_REFUSED");
    }

    @Test
    void anApprovalCarriesTheToolAndTheSummaryOfTheActionAsItsScope() {
        Line l = compose(JOURNAL).supervision().items().get(1);
        assertThat(l.scope()).isEqualTo("NETWORK curl");
        assertThat(l.text()).contains("Yes").contains("curl https://example.org");
        assertThat(l.actor()).isEqualTo("director");
        assertThat(l.at()).isEqualTo(T0.plusSeconds(5).toString());
    }

    @Test
    void everyLineNamesItsEventsAndTheNumbersStartAtOne() {
        OversightReport r = compose(JOURNAL);
        List<Line> all = new ArrayList<>();
        for (var s : List.of(r.supervision(), r.capabilities(), r.actions(), r.overrides())) all.addAll(s.items());
        assertThat(all).allSatisfy(l -> assertThat(l.events()).isNotEmpty());
        assertThat(r.capabilities().items().getFirst().events().getFirst().seq())
                .isEqualTo(1);
        assertThat(r.actions().items().getFirst().events())
                .extracting("type")
                .containsExactly("ActionRequested", "ActionAuthorized", "ActionExecuted");
    }

    @Test
    void actionOutcomesAreCodesAndAMissingOutcomeIsALimit() {
        OversightReport r = compose(JOURNAL);
        assertThat(r.actions().items())
                .extracting(Line::code)
                .containsExactly("EXECUTED", "DENIED_POLICY", "DENIED_DIRECTOR", "REQUESTED");
        assertThat(r.actions().items().getFirst().basis()).isEqualTo("DIRECTOR_APPROVAL");
        assertThat(r.limits()).contains("actions.withoutOutcome", "signIns.notJournaled");
    }

    @Test
    void aMissionWithoutRulesOrSignInsSaysSoAsLimitsAndClaimsNothing() {
        OversightReport r = compose(List.of(director(1, new MissionPlanApproved(M))));
        assertThat(r.capabilities().count()).isZero();
        assertThat(r.limits()).contains("permissionTable.none", "signIns.notJournaled");
        assertThat(r.actions().count()).isZero();
        assertThat(r.overrides().count()).isZero();
    }

    @Test
    void aBreakGlassSignInOfTheSpanIsCompanyWideWithoutANumber() {
        EventEnvelope sign = env(2, Actor.director(), null, null, new BreakGlassSignIn("s-1"));
        OversightReport r = compose(List.of(director(1, new MissionPlanApproved(M)), sign));
        Line l = r.supervision().items().get(1);
        assertThat(l.kind()).isEqualTo("BREAK_GLASS_SIGN_IN");
        assertThat(l.scope()).isEqualTo("COMPANY");
        assertThat(l.events().getFirst().seq()).isNull();
        assertThat(r.mission().events()).isEqualTo(1);
    }

    @Test
    void aQuarantineIsListedWithItsStepsAndNeverItsInputs() {
        EventEnvelope q =
                director(1, new MissionQuarantined("s1", ADA, M, T, "HIGH", List.of("SECRET_READ"), true, true, false));
        Line l = compose(List.of(q)).supervision().items().getFirst();
        assertThat(l.kind()).isEqualTo("QUARANTINE");
        assertThat(l.code()).isEqualTo("REVOKED+SUSPENDED");
        assertThat(l.basis()).isEqualTo("HIGH");
    }

    @Test
    void objectionsAreTheEventsOfAnObjectionsRound() {
        EventEnvelope round =
                env(1, Actor.director(), M, "objections-2", new DirectiveGiven(M, T, "Cover the edge case"));
        EventEnvelope plain = director(2, new DirectiveGiven(M, T, "Rename it"));
        OversightReport r = compose(List.of(round, plain));
        assertThat(kinds(r.overrides())).containsExactly("OBJECTION", "DIRECTIVE_GIVEN");
        assertThat(r.overrides().items().getFirst().code()).isEqualTo("2");
    }

    @Test
    void aSecretAndThePathOfTheMachineAreMaskedAndCounted() {
        EventEnvelope e = system(1, asked("a1", "Bash", "curl -H 'x-api-key: sk-ant-api03-abcdefghijklmnop' http://h"));
        OversightReport r = compose(List.of(e));
        assertThat(r.actions().items().getFirst().text()).doesNotContain("sk-ant-api03-abcdefghijklmnop");
        assertThat(r.masked()).isNotEmpty();
    }

    @Test
    void theSectionsAreCappedWithTheFullCount() {
        List<EventEnvelope> many = new ArrayList<>();
        for (int i = 1; i <= OversightComposer.CAP + 5; i++) many.add(system(i, asked("a" + i, "Bash", "ls")));
        OversightReport r = compose(many);
        assertThat(r.actions().count()).isEqualTo(OversightComposer.CAP + 5);
        assertThat(r.actions().items()).hasSize(OversightComposer.CAP);
    }

    @Test
    void theMarkdownMakesAgentTextInert() {
        EventEnvelope e = system(1, asked("a1", "Bash", "[click](http://evil.example) <img src=x> | `rm` ![i](u)"));
        String md = OversightMarkdown.render(compose(List.of(e)));
        assertThat(md).startsWith("# Human oversight report of M-1");
        assertThat(md).doesNotContain("<img").doesNotContain("](http://").doesNotContain("`rm`");
        assertThat(md).contains("&lt;img src=x&gt;").contains("\\[click\\]\\(http:\\/\\/evil.example\\)");
        assertThat(md)
                .contains("## Who supervised")
                .contains("## What humans overrode")
                .contains("limit: signIns.notJournaled");
    }
}
