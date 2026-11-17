package dev.floor42.domain;

import static dev.floor42.domain.AssurancePolicyTest.refusedWith;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.AssurancePolicy.ActKind;
import dev.floor42.domain.AssurancePolicy.Seat;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import dev.floor42.domain.event.EventEnvelope;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The acts without a command of their own (a grant, a tick, the identity settings): their signatures are checked. */
class AssuredIamActsTest {

    private static final Act GRANT = new Act(ActKind.GRANT, "agent:chris");
    private static final Act IAM = new Act(ActKind.IAM_SETTINGS, "iam");

    final Fixture f = new Fixture();

    ApprovalSigned sign(CommandContext ctx, Act act, Seat seat, String subject, String acr, Duration age) {
        return new ApprovalSigned(act, seat, subject, acr, ctx.now().minus(age).getEpochSecond(), "jti-" + subject);
    }

    List<EventEnvelope> record(CommandContext ctx, Act act, ApprovalSigned... signed) {
        return f.record(f.company.preferences().recordSignatures(ctx, act, List.of(signed)));
    }

    @Test
    void oneStrongFreshSignatureIsEnoughWithFourEyesOffAndIsJournaled() {
        CommandContext ctx = f.director();
        List<EventEnvelope> events = record(
                ctx, GRANT, sign(ctx, GRANT, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, Duration.ofSeconds(5)));
        assertThat(events).extracting(e -> e.event().getClass().getSimpleName()).containsExactly("ApprovalSigned");
    }

    @Test
    void withoutASignatureOrWithAWeakOrStaleOneTheActIsRefused() {
        CommandContext ctx = f.director();
        refusedWith(Refusal.ASSURANCE_MISSING, () -> record(ctx, IAM));
        refusedWith(
                Refusal.ASSURANCE_TOO_LOW,
                () -> record(ctx, IAM, sign(ctx, IAM, Seat.FIRST, "sub-a", "1", Duration.ofSeconds(5))));
        refusedWith(
                Refusal.STEP_UP_STALE,
                () -> record(
                        ctx,
                        IAM,
                        sign(ctx, IAM, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, Duration.ofMinutes(10))));
        refusedWith(
                Refusal.ASSURANCE_MISSING,
                () -> record(
                        ctx,
                        IAM,
                        sign(ctx, GRANT, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, Duration.ofSeconds(5))));
    }

    @Test
    void withFourEyesOnTwoDifferentPeopleSign() {
        f.record(f.company.preferences().changeSetting(f.director(), Setting.FOUR_EYES, "on"));
        CommandContext ctx = f.director();
        ApprovalSigned a = sign(ctx, GRANT, Seat.FIRST, "sub-a", AssurancePolicy.ACR_STRONG, Duration.ofSeconds(5));
        refusedWith(Refusal.ASSURANCE_MISSING, () -> record(ctx, GRANT, a));
        refusedWith(
                Refusal.SAME_PERSON,
                () -> record(
                        ctx,
                        GRANT,
                        a,
                        sign(ctx, GRANT, Seat.SECOND, "sub-a", AssurancePolicy.ACR_STRONG, Duration.ofSeconds(5))));
        List<EventEnvelope> events = record(
                ctx,
                GRANT,
                a,
                sign(ctx, GRANT, Seat.SECOND, "sub-b", AssurancePolicy.ACR_STRONG, Duration.ofSeconds(5)));
        assertThat(events).hasSize(2);
    }

    @Test
    void anAgentCannotRecordThem() {
        CommandContext agent = f.agent(Fixture.CHRIS);
        refusedWith(Refusal.DIRECTOR_ONLY, () -> f.company.preferences().recordSignatures(agent, GRANT, List.of()));
    }

    @Test
    void theImportIsRememberedFromTheJournal() {
        assertThat(f.company.preferences().authorizationImported()).isFalse();
        var t = new AuthorizationTuple("agent:chris", "granted", "resource:jira");
        f.record(f.company
                .preferences()
                .changeAuthorization(f.director(), List.of(t), List.of(), "m1", "GRANT", false, false));
        assertThat(f.company.preferences().authorizationImported()).isFalse();
        f.record(f.company
                .preferences()
                .changeAuthorization(f.system(), List.of(t), List.of(), "m1", "IMPORT", true, false));
        assertThat(f.company.preferences().authorizationImported()).isTrue();
    }
}
