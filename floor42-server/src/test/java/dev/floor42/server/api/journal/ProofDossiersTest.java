package dev.floor42.server.api.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import dev.floor42.domain.event.WorkbenchEvent.PatchAccepted;
import dev.floor42.server.app.delivery.MissionReports;
import dev.floor42.server.app.delivery.MissionReports.CostBound;
import dev.floor42.server.app.delivery.MissionReports.Measure;
import dev.floor42.server.app.delivery.MissionReports.MissionReport;
import dev.floor42.server.app.delivery.MissionReports.ReportOutput;
import dev.floor42.server.app.delivery.MissionReports.ReportTotals;
import dev.floor42.server.iam.IdentityAudit;
import dev.floor42.server.iam.MissionDelegation;
import dev.floor42.server.security.ReplaySanitizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The dossier composed from hand-built envelopes: no Quarkus, no git, no journal file. */
class ProofDossiersTest {

    private static final MissionId M = new MissionId("M-1");
    private static final TicketId T = new TicketId("T-1");
    private static final AgentId ADA = new AgentId("ada");
    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static EventEnvelope env(int n, Origin origin, Actor actor, DomainEvent e) {
        return new EventEnvelope("e" + n, T0.plusSeconds(n), origin, actor, M, null, null, e);
    }

    private static EventEnvelope env(int n, DomainEvent e) {
        return env(n, Origin.REAL, Actor.system(), e);
    }

    private static ActionRequested asked(String id, String tool, String summary) {
        return new ActionRequested(new ActionId(id), M, T, ADA, ActionCategory.NETWORK, tool, summary);
    }

    private static Mission mission(Origin origin) {
        EventEnvelope created = env(0, origin, Actor.director(), new MissionCreated(M, "Title", "Objective", null));
        return Company.replay(List.of(created)).missions().find(M).orElseThrow();
    }

    private static MissionReport report(ReportTotals totals, List<ReportOutput> outputs) {
        return new MissionReport(
                "M-1",
                "Title",
                "ACTIVE",
                "REAL",
                null,
                null,
                null,
                "2026-11-13T11:00:00Z",
                totals,
                List.of(),
                null,
                List.of(),
                null,
                outputs,
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of());
    }

    private static ReportTotals totals(Measure cost, CostBound bound) {
        return new ReportTotals(0, 0, 0, 0, 0, null, null, cost, bound, null);
    }

    private static ProofDossier compose(List<EventEnvelope> events) {
        return compose(Origin.REAL, events, report(totals(null, null), List.of()), Optional.empty());
    }

    private static ProofDossier compose(
            Origin origin, List<EventEnvelope> events, MissionReport report, Optional<MissionDelegation> delegation) {
        return ProofDossiers.compose(
                mission(origin),
                events,
                report,
                delegation,
                Map.of(),
                new ReplaySanitizer("tok-secret-value", Map.of()),
                "1.1.0",
                T0.plusSeconds(3600));
    }

    private static final List<EventEnvelope> IAM = List.of(
            env(1, new AgentTokenIssued("L1", ADA, M, T, "http://kc/realms/floor42", "2026-11-13T18:00:00Z", "jti-1")),
            env(2, asked("a1", "Bash", "git push origin main")),
            env(3, new ActionDenied(new ActionId("a1"), DenialReason.POLICY, "pushes are refused")),
            env(4, asked("a2", IdentityAudit.BROKER_TOOL, "github")),
            env(
                    5,
                    new ActionExecuted(
                            new ActionId("a2"),
                            "Resource github GET /user -> 200 (exchanged token jti-9 for target-api)\nbody",
                            true)),
            env(6, asked("a3", "Bash", "curl")),
            env(7, new ActionDenied(new ActionId("a3"), DenialReason.BLOCKED_UPSTREAM, "identity: WRONG_PROCESS")));

    @Test
    void aMissionWithoutTokenSaysIamOffAndListsNoIdentity() {
        ProofDossier d = compose(List.of(env(1, new BudgetSet(M, 5.0))));
        assertThat(d.iam()).isFalse();
        assertThat(d.identities().count()).isZero();
        assertThat(d.delegation()).isNull();
    }

    @Test
    void anIssuedTokenShowsJtiIssuerExpiryFromTheEvent() {
        ProofDossier.IdentityFact f = compose(IAM).identities().items().getFirst();
        assertThat(f.jti()).isEqualTo("jti-1");
        assertThat(f.issuer()).isEqualTo("http://kc/realms/floor42");
        assertThat(f.expiresAt()).isEqualTo("2026-11-13T18:00:00Z");
        assertThat(f.ref().source()).isEqualTo("JOURNAL");
        assertThat(f.ref().events()).extracting(ProofDossier.EventRef::type).containsExactly("AgentTokenIssued");
    }

    @Test
    void theClientNameIsMarkedDerivedNotJournal() {
        ProofDossier.IdentityFact f = compose(IAM).identities().items().getFirst();
        assertThat(f.client()).isEqualTo("floor42-agent-ada");
        assertThat(f.derived()).containsExactly("client", "onBehalfOf", "tokenAudience");
    }

    @Test
    void aBrokerCallShowsTheAudienceReadFromItsResultLine() {
        ProofDossier.BrokerFact b = compose(IAM).brokerCalls().items().getFirst();
        assertThat(b.resource()).isEqualTo("github");
        assertThat(b.method()).isEqualTo("GET");
        assertThat(b.status()).isEqualTo("200");
        assertThat(b.exchangedJti()).isEqualTo("jti-9");
        assertThat(b.audience()).isEqualTo("target-api");
        assertThat(b.ref().events()).extracting(ProofDossier.EventRef::seq).containsExactly(4, 5);
    }

    @Test
    void aBrokerCallStillReachesTheDossierAfterTheJournalMaskedItsResult() {
        String jti = "09d81046-33d1-4312-9e6f-3925d03a06b7";
        String masked = dev.floor42.server.app.session.BrokerLine.mask(
                "Resource github GET /user -> 200 (exchanged token " + jti + " for target-api)\nbody",
                dev.floor42.server.runtime.ToolCatalog.FLOOR42_TOOL_PREFIX + IdentityAudit.BROKER_TOOL);
        List<EventEnvelope> events =
                List.of(IAM.get(0), IAM.get(3), env(5, new ActionExecuted(new ActionId("a2"), masked, true)));
        ProofDossier.BrokerFact b = compose(events).brokerCalls().items().getFirst();
        assertThat(b.exchangedJti()).isEqualTo(jti);
        assertThat(b.audience()).isEqualTo("target-api");
    }

    @Test
    void anIdentityRefusalIsARefusalMarkedIdentity() {
        List<ProofDossier.RefusalFact> r = compose(IAM).refusals().items();
        assertThat(r).extracting(ProofDossier.RefusalFact::identity).containsExactly(false, true);
    }

    @Test
    void theDelegationIsMarkedStateAndNeverJournal() {
        MissionDelegation d =
                new MissionDelegation(M, "vault-entry-name", T0, T0.plusSeconds(60), List.of("github"), false);
        ProofDossier out = compose(Origin.REAL, IAM, report(totals(null, null), List.of()), Optional.of(d));
        assertThat(out.delegation().ref().source()).isEqualTo("STATE");
        assertThat(out.delegation().ref().events()).isEmpty();
        assertThat(out.delegation().resources()).containsExactly("github");
        assertThat(asJson(out).toString()).doesNotContain("vault-entry-name");
    }

    private static final String SUB = "7f3c9a10-1111-2222-3333-444455556666";

    private static List<EventEnvelope> withClaims() {
        List<EventEnvelope> out = new ArrayList<>(IAM);
        out.set(
                0,
                env(
                        1,
                        new AgentTokenIssued(
                                "L1",
                                ADA,
                                M,
                                T,
                                "http://kc/realms/floor42",
                                "2026-11-13T18:00:00Z",
                                "jti-1",
                                SUB,
                                "floor42-agent-ada",
                                "floor42-agent-ada",
                                List.of("openid", "profile"))));
        return out;
    }

    @Test
    void claimsAreShownAsRecorded() {
        ProofDossier out = compose(withClaims());
        ProofDossier.IdentityFact f = out.identities().items().getFirst();
        assertThat(f.authorizedParty()).isEqualTo("floor42-agent-ada");
        assertThat(f.actor()).isEqualTo("floor42-agent-ada");
        assertThat(f.scopes()).containsExactly("openid", "profile");
        assertThat(f.ref().source()).isEqualTo("JOURNAL");
        assertThat(out.limits()).doesNotContain("identity.claimsNotJournaled");
    }

    @Test
    void anOlderSessionKeepsTheLimit() {
        ProofDossier out = compose(IAM);
        ProofDossier.IdentityFact f = out.identities().items().getFirst();
        assertThat(f.subject()).isNull();
        assertThat(f.scopes()).isNull();
        assertThat(f.derived()).containsExactly("client", "onBehalfOf", "tokenAudience");
        assertThat(out.limits()).contains("identity.claimsNotJournaled");
    }

    @Test
    void theSubjectIsShortenedInTheDossier() {
        ProofDossier out = compose(withClaims());
        assertThat(out.identities().items().getFirst().subject()).isEqualTo("7f3c9a10…");
        assertThat(asJson(out).toString()).doesNotContain(SUB);
    }

    @Test
    void aDeniedPushIsListedWithItsReasonAndItsRequest() {
        ProofDossier.RefusalFact r = compose(IAM).refusals().items().getFirst();
        assertThat(r.reason()).isEqualTo("POLICY");
        assertThat(r.summary()).isEqualTo("git push origin main");
        assertThat(r.detail()).isEqualTo("pushes are refused");
        assertThat(r.ref().events())
                .extracting(ProofDossier.EventRef::type)
                .containsExactly("ActionRequested", "ActionDenied");
    }

    @Test
    void planApprovalNamesTheActorOfTheEnvelopeAndAuthorizedActionsAreCountedByBasis() {
        ProofDossier d = compose(List.of(
                env(1, Origin.REAL, Actor.director(), new MissionPlanApproved(M)),
                env(2, new ActionAuthorized(new ActionId("a1"), "POLICY")),
                env(3, new ActionAuthorized(new ActionId("a2"), "POLICY")),
                env(4, new ActionAuthorized(new ActionId("a3"), "DIRECTOR"))));
        assertThat(d.approvals().items().getFirst().actor()).isEqualTo("director");
        assertThat(d.approvals().items().getFirst().kind()).isEqualTo("PLAN_APPROVED");
        assertThat(d.authorized())
                .containsExactly(new ProofDossier.BasisCount("POLICY", 2), new ProofDossier.BasisCount("DIRECTOR", 1));
    }

    private static List<EventEnvelope> sessions(Origin origin, Double estimated, Double bound) {
        return List.of(
                env(1, origin, Actor.system(), new AgentSessionStarted("S1", M, T, ADA, "model", "1", 2.0)),
                env(
                        2,
                        origin,
                        Actor.system(),
                        new AgentSessionEnded("S1", false, "done", 3, 10L, 20L, estimated, bound)));
    }

    @Test
    void aRealSessionCostIsEstimatedAndASimulatedOneIsSimulatedZero() {
        ProofDossier real = compose(
                Origin.REAL,
                sessions(Origin.REAL, 1.25, null),
                report(totals(new Measure(1.25, MissionReports.ESTIMATED), null), List.of()),
                Optional.empty());
        assertThat(real.costs().sessions().getFirst().basis()).isEqualTo("ESTIMATED");
        assertThat(real.costs().sessions().getFirst().usd()).isEqualTo(1.25);
        assertThat(real.costs().total().basis()).isEqualTo("ESTIMATED");
        ProofDossier simulated = compose(
                Origin.SIMULATED,
                sessions(Origin.SIMULATED, 9.0, null),
                report(totals(new Measure(0.0, MissionReports.SIMULATED), null), List.of()),
                Optional.empty());
        assertThat(simulated.costs().sessions().getFirst().usd()).isZero();
        assertThat(simulated.costs().sessions().getFirst().basis()).isEqualTo("SIMULATED");
    }

    @Test
    void anUpperBoundIsNeverAddedToTheTotalAndNoCostReportedGivesNullNotZero() {
        ProofDossier d = compose(
                Origin.REAL,
                sessions(Origin.REAL, null, 2.0),
                report(totals(null, new CostBound(new Measure(2.0, MissionReports.UPPER_BOUND), 1)), List.of()),
                Optional.empty());
        assertThat(d.costs().total()).isNull();
        assertThat(d.costs().sessions().getFirst().usd()).isNull();
        assertThat(d.costs().bound().usd()).isEqualTo(2.0);
        assertThat(d.costs().bound().basis()).isEqualTo("UPPER_BOUND");
    }

    @Test
    void checksRanCarryExitCodeDurationAndAreMeasuredAndATimedOutOneHasNoExitCode() {
        ProofDossier d = compose(List.of(
                env(1, new ChecksRan(M, ADA, "mvn test", 0, false, 1200, "ok", "0123456789abcdef0123", "manual")),
                env(2, new ChecksRan(M, ADA, "mvn verify", null, true, 120_000, "", "abc", "automatic"))));
        List<ProofDossier.CheckFact> c = d.checks().items();
        assertThat(c.getFirst().exitCode()).isZero();
        assertThat(c.getFirst().durationMs()).isEqualTo(1200);
        assertThat(c.getFirst().ref().basis()).isEqualTo("MEASURED");
        assertThat(c.getFirst().head()).hasSize(12);
        assertThat(c.get(1).exitCode()).isNull();
        assertThat(c.get(1).timedOut()).isTrue();
    }

    @Test
    void theDiffIsMarkedGitWithItsReadTimeAndAnUnreadableWorktreeIsSaidNotCounted() {
        List<ReportOutput> outputs = List.of(
                new ReportOutput("ada", "/w", "floor42/ada", true, 2, List.of("a"), 1, List.of(), 0, null),
                new ReportOutput("bob", "/x", "floor42/bob", false, -1, List.of(), 0, List.of(), 0, null));
        ProofDossier d = compose(Origin.REAL, List.of(), report(totals(null, null), outputs), Optional.empty());
        assertThat(d.diff().items()).extracting(f -> f.ref().source()).containsOnly("GIT");
        assertThat(d.gitReadAt()).isEqualTo("2026-11-13T11:00:00Z");
        assertThat(d.diff().items().get(1).readable()).isFalse();
        assertThat(d.diff().items().get(1).commits()).isEqualTo(-1);
    }

    @Test
    void sectionsAreCappedAt200AndKeepTheFullCount() {
        List<EventEnvelope> many = new ArrayList<>();
        for (int i = 1; i <= 250; i++)
            many.add(env(i, new ChecksRan(M, ADA, "mvn " + i, 0, false, 1, "", "abc", "manual")));
        ProofDossier d = compose(many);
        assertThat(d.checks().count()).isEqualTo(250);
        assertThat(d.checks().items()).hasSize(ProofDossiers.CAP);
    }

    @Test
    void eventNumbersMatchTheReplayExportNumbering() {
        ProofDossier d = compose(IAM);
        // The replay numbers the mission's events from 1 in journal order.
        assertThat(d.identities().items().getFirst().ref().events().getFirst().seq())
                .isEqualTo(1);
        assertThat(d.refusals().items().get(1).ref().events().getLast().seq()).isEqualTo(7);
        assertThat(d.mission().events()).isEqualTo(IAM.size());
    }

    @Test
    void everyJournalFactCitesAtLeastOneEventWithItsOrigin() {
        List<JsonNode> facts = new ArrayList<>();
        journalRefs(asJson(compose(IAM)), facts);
        assertThat(facts).isNotEmpty();
        for (JsonNode ref : facts) {
            assertThat(ref.get("events")).isNotEmpty();
            assertThat(ref.get("origin").asText()).isIn("REAL", "SIMULATED");
        }
    }

    @Test
    void theSanitizerMasksTextsAndTheMissionTitleGoesThroughIt() {
        ProofDossier d = compose(List.of(
                env(1, asked("a1", "Bash", "echo sk-ant-api03-abcdefghijklmnop tok-secret-value")),
                env(2, new ActionDenied(new ActionId("a1"), DenialReason.POLICY, "no"))));
        String json = asJson(d).toString();
        assertThat(json).doesNotContain("sk-ant-api03-abcdefghijklmnop", "tok-secret-value");
        assertThat(d.masked()).extracting(ProofDossier.MaskCount::kind).contains("SECRET", "TOKEN");
    }

    private static JsonNode asJson(ProofDossier d) {
        return JSON.valueToTree(d);
    }

    private static void fieldNames(JsonNode n, List<String> out) {
        n.fieldNames().forEachRemaining(out::add);
        n.forEach(c -> fieldNames(c, out));
    }

    private static void journalRefs(JsonNode n, List<JsonNode> out) {
        if (n.has("source") && "JOURNAL".equals(n.get("source").asText())) out.add(n);
        n.forEach(c -> journalRefs(c, out));
    }

    @Test
    void anAcceptedCommitShowsWhatGitSaidOfItsSignature() {
        String sha = "a".repeat(40);
        String other = "b".repeat(40);
        List<EventEnvelope> events = List.of(
                env(1, new PatchAccepted(M, ADA, sha, "one", List.of("a.txt"))),
                env(2, new PatchAccepted(M, ADA, other, "two", List.of("b.txt"))));
        ProofDossier d = ProofDossiers.compose(
                mission(Origin.REAL),
                events,
                report(totals(null, null), List.of()),
                Optional.empty(),
                Map.of(sha, new ProofDossier.SignatureFact("VERIFIED", "ada", false)),
                new ReplaySanitizer("tok-secret-value", Map.of()),
                "1.3.0",
                T0.plusSeconds(3600));
        List<ProofDossier.DeliveryFact> items = d.deliverables().items();
        assertThat(items.get(0).signature().status()).isEqualTo("VERIFIED");
        assertThat(items.get(0).signature().agent()).isEqualTo("ada");
        // No word from git, no claim: the line carries no signature at all
        assertThat(items.get(1).signature()).isNull();
    }
}
