package dev.floor42.domain;

import static dev.floor42.domain.AssurancePolicyTest.refusedWith;
import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.AssurancePolicy.ActKind;
import dev.floor42.domain.AssurancePolicy.Seat;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.EventEnvelope;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** An ALLOW that reaches outside is enforced in the domain, not only in the route (ADR-0081). */
class ApprovalPolicyTest {

    private static final String CALL_MCP = "mcp__floor42__call_mcp";

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    /** A context of the director where a stronger proof exists, at the fixture's clock. */
    CommandContext assured() {
        return f.director().withAssurance();
    }

    private final Set<String> ruled = new HashSet<>();

    QuestionId ask(String id, ActionCategory category, String tool, String summary) {
        if (ruled.add(tool)) {
            f.record(f.company
                    .missions()
                    .addPermission(f.director(), m, PermissionRule.forTool(tool, category, Decision.ASK)));
        }
        f.record(f.company.actions().request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, category, tool, summary));
        return f.company.desk().open().getLast().id();
    }

    ApprovalSigned sign(CommandContext ctx, QuestionId q, Seat seat, String subject, String acr, long ageSeconds) {
        return new ApprovalSigned(
                new Act(ActKind.QUESTION, q.value()),
                seat,
                subject,
                acr,
                ctx.now().minusSeconds(ageSeconds).getEpochSecond(),
                "jti");
    }

    List<EventEnvelope> allow(CommandContext ctx, QuestionId q, ApprovalSigned... signed) {
        return f.record(f.company.desk().answer(ctx, q, "ALLOW", null, List.of(signed)));
    }

    @Test
    void anAssuredAllowWithoutASignatureIsRefusedByTheDomain() {
        QuestionId q = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        refusedWith(Refusal.ASSURANCE_MISSING, () -> f.company.desk().answer(assured(), q, "ALLOW"));
        refusedWith(Refusal.ASSURANCE_MISSING, () -> allow(assured(), q));
        assertThat(f.company.desk().find(q).orElseThrow().status()).isEqualTo(QuestionStatus.OPEN);
        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().stage())
                .isEqualTo(ActionStage.REQUESTED);
    }

    @Test
    void aSignedAllowIsRecordedWithItsSignatureBeforeTheAnswer() {
        QuestionId q = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        CommandContext ctx = assured();
        List<EventEnvelope> events = allow(ctx, q, sign(ctx, q, Seat.FIRST, "sub-a", "floor42-strong", 5));
        assertThat(events.getFirst().event()).isInstanceOf(ApprovalSigned.class);
        assertThat(events.get(1).event()).isInstanceOf(QuestionAnswered.class);
        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().stage())
                .isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void anAllowWithoutTheStrongAcrIsRefusedAndNothingIsRecorded() {
        QuestionId q = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        CommandContext ctx = assured();
        long before = f.company.version();
        refusedWith(
                Refusal.ASSURANCE_TOO_LOW, () -> allow(ctx, q, sign(ctx, q, Seat.FIRST, "sub-a", "floor42-basic", 5)));
        assertThat(f.company.version()).isEqualTo(before);
    }

    @Test
    void aStaleSignInIsRefused() {
        QuestionId q = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        CommandContext ctx = assured();
        refusedWith(
                Refusal.STEP_UP_STALE, () -> allow(ctx, q, sign(ctx, q, Seat.FIRST, "sub-a", "floor42-strong", 3_600)));
    }

    @Test
    void aSignatureForAnotherQuestionDoesNotCount() {
        QuestionId q1 = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        QuestionId q2 = ask("a2", ActionCategory.NETWORK, CALL_MCP, "srv-b search {}");
        CommandContext ctx = assured();
        ApprovalSigned forQ1 = sign(ctx, q1, Seat.FIRST, "sub-a", "floor42-strong", 5);
        refusedWith(Refusal.ASSURANCE_MISSING, () -> allow(ctx, q2, forQ1));
        assertThat(f.company.desk().find(q2).orElseThrow().status()).isEqualTo(QuestionStatus.OPEN);
    }

    @Test
    void aDenyNeedsNoSignature() {
        QuestionId q = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        f.record(f.company.desk().answer(assured(), q, "DENY", null, List.of()));
        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().stage())
                .isEqualTo(ActionStage.DENIED);
    }

    @Test
    void aGetAnswerStillNeedsNoStepUp() {
        QuestionId q = ask("a1", ActionCategory.NETWORK, "WebFetch", "https://example.invalid/page");
        f.record(f.company.desk().answer(assured(), q, "ALLOW"));
        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().stage())
                .isEqualTo(ActionStage.AUTHORIZED);
        QuestionId shell = ask("a2", ActionCategory.EXECUTE, "Bash", "ls");
        f.record(f.company.desk().answer(assured(), shell, "ALLOW"));
        assertThat(f.company.actions().find(new ActionId("a2")).orElseThrow().stage())
                .isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void whatTheAnswerNeedsIsKnownBeforeItIsGiven() {
        QuestionId q = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        assertThat(f.company.desk().assuranceFor(q, "ALLOW")).isEqualTo(AssurancePolicy.Level.STRONG);
        assertThat(f.company.desk().assuranceFor(q, "DENY")).isEqualTo(AssurancePolicy.Level.BASIC);
        QuestionId get = ask("a2", ActionCategory.NETWORK, "WebSearch", "weather");
        assertThat(f.company.desk().assuranceFor(get, "ALLOW")).isEqualTo(AssurancePolicy.Level.BASIC);
    }

    @Test
    void withoutAStrongerProofTheClickIsEnough() {
        // IAM mode off: the context is not assured, nothing changes for anyone (ExternalMcpApprovalTest relies on it).
        QuestionId q = ask("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        f.record(f.company.desk().answer(f.director(), q, "ALLOW"));
        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().stage())
                .isEqualTo(ActionStage.AUTHORIZED);
    }
}
