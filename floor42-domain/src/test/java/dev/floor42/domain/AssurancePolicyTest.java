package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.AssurancePolicy.ActKind;
import dev.floor42.domain.AssurancePolicy.Level;
import dev.floor42.domain.AssurancePolicy.Seat;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The acts that need more than a click, as a table (ADR-0081), and what proves them. */
class AssurancePolicyTest {

    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    static final CommandContext CTX = new CommandContext(Actor.director(), Origin.REAL, NOW, null, true);
    static final Act ACT = new Act(ActKind.QUESTION, "Q-1");

    static ApprovalSigned sign(Act act, Seat seat, String subject, String acr, Instant authenticatedAt) {
        return new ApprovalSigned(act, seat, subject, acr, authenticatedAt.getEpochSecond(), "jti-" + subject);
    }

    static ApprovalSigned first() {
        return sign(ACT, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, NOW.minusSeconds(5));
    }

    static ApprovalSigned second() {
        return sign(ACT, Seat.SECOND, "sub-b", AssurancePolicy.ACR_STRONG, NOW.minusSeconds(5));
    }

    static void refusedWith(Refusal refusal, Runnable run) {
        assertThatThrownBy(run::run)
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code()).isEqualTo(refusal.code()));
    }

    @Test
    void theTableRowByRowWithFourEyesOffAndOn() {
        // kind, four eyes off, four eyes on
        Object[][] table = {
            {ActKind.PLAN, Level.STRONG, Level.STRONG},
            {ActKind.QUESTION, Level.STRONG, Level.STRONG},
            {ActKind.RULE, Level.STRONG, Level.FOUR_EYES},
            {ActKind.GRANT, Level.STRONG, Level.FOUR_EYES},
            {ActKind.PULL_REQUEST, Level.STRONG, Level.FOUR_EYES},
            {ActKind.TRUST, Level.STRONG, Level.FOUR_EYES},
            {ActKind.SCRIPT_TOKEN, Level.STRONG, Level.STRONG},
            {ActKind.SETTING, Level.FOUR_EYES, Level.FOUR_EYES},
            {ActKind.IAM_SETTINGS, Level.STRONG, Level.FOUR_EYES},
        };
        assertThat(table.length).isEqualTo(ActKind.values().length);
        for (Object[] row : table) {
            assertThat(AssurancePolicy.required((ActKind) row[0], false))
                    .as("%s, off", row[0])
                    .isEqualTo(row[1]);
            assertThat(AssurancePolicy.required((ActKind) row[0], true))
                    .as("%s, on", row[0])
                    .isEqualTo(row[2]);
        }
    }

    private static Action call(ActionCategory category, String tool) {
        return new Action(
                new ActionId("a1"),
                new MissionId("M-1"),
                new TicketId("T-1"),
                new AgentId("chris"),
                category,
                tool,
                "summary",
                NOW);
    }

    @Test
    void anAllowToACallThatReachesOutsideIsStrongAndAPlainReadKeepsTheClick() {
        assertThat(AssurancePolicy.forQuestion(call(ActionCategory.NETWORK, "mcp__floor42__call_mcp")))
                .isEqualTo(Level.STRONG);
        assertThat(AssurancePolicy.forQuestion(call(ActionCategory.NETWORK, "SomeNewHttpTool")))
                .isEqualTo(Level.STRONG);
        assertThat(AssurancePolicy.forQuestion(call(ActionCategory.NETWORK, "WebFetch")))
                .isEqualTo(Level.BASIC);
        assertThat(AssurancePolicy.forQuestion(call(ActionCategory.NETWORK, "WebSearch")))
                .isEqualTo(Level.BASIC);
        for (ActionCategory c : List.of(ActionCategory.READ, ActionCategory.WRITE, ActionCategory.EXECUTE)) {
            assertThat(AssurancePolicy.forQuestion(call(c, "Bash"))).as("%s", c).isEqualTo(Level.BASIC);
        }
    }

    @Test
    void aRuleIsRiskyOnlyWhenItAllowsShellOrNetwork() {
        for (ActionCategory c : ActionCategory.values()) {
            for (Decision d : Decision.values()) {
                boolean risky = d == Decision.ALLOW && (c == ActionCategory.EXECUTE || c == ActionCategory.NETWORK);
                assertThat(AssurancePolicy.forRule(c, d, false))
                        .as("%s %s, off", c, d)
                        .isEqualTo(risky ? Level.STRONG : Level.BASIC);
                assertThat(AssurancePolicy.forRule(c, d, true))
                        .as("%s %s, on", c, d)
                        .isEqualTo(risky ? Level.FOUR_EYES : Level.BASIC);
            }
        }
    }

    // ---- verify

    @Test
    void aBasicActNeedsNothingAndItsSignaturesAreNotKept() {
        assertThat(AssurancePolicy.verify(CTX, ACT, Level.BASIC, null)).isEmpty();
        assertThat(AssurancePolicy.verify(CTX, ACT, Level.BASIC, List.of(first())))
                .isEmpty();
    }

    @Test
    void aStrongActNeedsOneFreshStrongSignatureForThatAct() {
        assertThat(AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(first())))
                .containsExactly(first());
    }

    @Test
    void noSignatureIsRefusedAsMissing() {
        refusedWith(Refusal.ASSURANCE_MISSING, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, null));
        refusedWith(Refusal.ASSURANCE_MISSING, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of()));
    }

    @Test
    void aLowerLevelIsRefused() {
        ApprovalSigned basic = sign(ACT, Seat.FIRST, "sub-a", "floor42-basic", NOW.minusSeconds(5));
        refusedWith(Refusal.ASSURANCE_TOO_LOW, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(basic)));
        ApprovalSigned none = sign(ACT, Seat.FIRST, "sub-a", null, NOW.minusSeconds(5));
        refusedWith(Refusal.ASSURANCE_TOO_LOW, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(none)));
    }

    @Test
    void aSignInOlderThan120SecondsIsStale() {
        ApprovalSigned late = sign(
                ACT,
                Seat.FIRST,
                "sub-a",
                AssurancePolicy.ACR_STRONG,
                NOW.minus(AssurancePolicy.FRESH).minusSeconds(1));
        refusedWith(Refusal.STEP_UP_STALE, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(late)));
        ApprovalSigned edge =
                sign(ACT, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, NOW.minus(AssurancePolicy.FRESH));
        assertThat(AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(edge)))
                .hasSize(1);
    }

    @Test
    void aSignInClaimingAFutureTimeIsRefused() {
        ApprovalSigned ahead =
                sign(ACT, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, NOW.plus(Duration.ofHours(1)));
        refusedWith(Refusal.STEP_UP_STALE, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(ahead)));
    }

    @Test
    void aSignatureForAnotherActOrAnotherSeatDoesNotCount() {
        Act other = new Act(ActKind.QUESTION, "Q-2");
        ApprovalSigned forOther = sign(other, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, NOW);
        refusedWith(Refusal.ASSURANCE_MISSING, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(forOther)));
        ApprovalSigned kind =
                sign(new Act(ActKind.SETTING, "Q-1"), Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, NOW);
        refusedWith(Refusal.ASSURANCE_MISSING, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(kind)));
        refusedWith(Refusal.ASSURANCE_MISSING, () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(second())));
    }

    @Test
    void moreSignaturesThanTheActNeedsIsAnInvalidRequest() {
        refusedWith(
                Refusal.INVALID_ARGUMENT,
                () -> AssurancePolicy.verify(CTX, ACT, Level.STRONG, List.of(first(), second())));
    }

    @Test
    void fourEyesNeedsTwoDifferentPeople() {
        assertThat(AssurancePolicy.verify(CTX, ACT, Level.FOUR_EYES, List.of(first(), second())))
                .hasSize(2);
        refusedWith(
                Refusal.ASSURANCE_MISSING, () -> AssurancePolicy.verify(CTX, ACT, Level.FOUR_EYES, List.of(first())));
        ApprovalSigned same = sign(ACT, Seat.SECOND, "sub-a", AssurancePolicy.ACR_STRONG, NOW.minusSeconds(5));
        refusedWith(
                Refusal.SAME_PERSON, () -> AssurancePolicy.verify(CTX, ACT, Level.FOUR_EYES, List.of(first(), same)));
    }

    @Test
    void theFirstSignatureMayWaitThirtyMinutesButTheSecondMustBeFresh() {
        ApprovalSigned waited = sign(
                ACT, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, NOW.minus(AssurancePolicy.COUNTERSIGN_WINDOW));
        assertThat(AssurancePolicy.verify(CTX, ACT, Level.FOUR_EYES, List.of(waited, second())))
                .hasSize(2);
        ApprovalSigned expired = sign(
                ACT,
                Seat.FIRST,
                "sub-a",
                AssurancePolicy.ACR_STRONG,
                NOW.minus(AssurancePolicy.COUNTERSIGN_WINDOW).minusSeconds(1));
        refusedWith(
                Refusal.SIGNATURE_EXPIRED,
                () -> AssurancePolicy.verify(CTX, ACT, Level.FOUR_EYES, List.of(expired, second())));
        ApprovalSigned stale = sign(
                ACT,
                Seat.SECOND,
                "sub-b",
                AssurancePolicy.ACR_STRONG,
                NOW.minus(AssurancePolicy.FRESH).minusSeconds(1));
        refusedWith(
                Refusal.STEP_UP_STALE,
                () -> AssurancePolicy.verify(CTX, ACT, Level.FOUR_EYES, List.of(first(), stale)));
    }

    @Test
    void theSecondSignatureNeedsTheStrongLevelToo() {
        ApprovalSigned basic = sign(ACT, Seat.SECOND, "sub-b", "floor42-basic", NOW.minusSeconds(5));
        refusedWith(
                Refusal.ASSURANCE_TOO_LOW,
                () -> AssurancePolicy.verify(CTX, ACT, Level.FOUR_EYES, List.of(first(), basic)));
    }
}
