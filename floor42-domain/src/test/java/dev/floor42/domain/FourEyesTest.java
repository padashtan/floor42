package dev.floor42.domain;

import static dev.floor42.domain.AssurancePolicyTest.refusedWith;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.AssurancePolicy.ActKind;
import dev.floor42.domain.AssurancePolicy.Seat;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.PreferenceEvent.SettingChanged;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Four eyes (ADR-0081): off by default, and turning it off is an act of two signatures by two people. */
class FourEyesTest {

    private static final Act ACT = new Act(ActKind.SETTING, "FOUR_EYES");

    final Fixture f = new Fixture();

    ApprovalSigned sign(CommandContext ctx, Seat seat, String subject, Duration age) {
        return new ApprovalSigned(
                ACT, seat, subject, "floor42-strong", ctx.now().minus(age).getEpochSecond(), "jti-" + subject);
    }

    void turnOn() {
        f.record(f.company.preferences().changeSetting(f.director(), Setting.FOUR_EYES, "on"));
    }

    List<EventEnvelope> turnOff(CommandContext ctx, ApprovalSigned... signed) {
        return f.record(f.company.preferences().changeSetting(ctx, Setting.FOUR_EYES, "off", List.of(signed)));
    }

    @Test
    void fourEyesIsOffByDefaultAndTurningItOnIsAClick() {
        assertThat(f.company.preferences().setting(Setting.FOUR_EYES)).isEmpty();
        turnOn();
        assertThat(f.company.preferences().setting(Setting.FOUR_EYES)).contains("on");
    }

    @Test
    void turningFourEyesOffNeedsTwoSignatures() {
        turnOn();
        CommandContext ctx = f.director().withAssurance();
        refusedWith(Refusal.ASSURANCE_MISSING, () -> turnOff(ctx));
        refusedWith(
                Refusal.ASSURANCE_MISSING, () -> turnOff(ctx, sign(ctx, Seat.FIRST, "sub-a", Duration.ofSeconds(5))));
        assertThat(f.company.preferences().setting(Setting.FOUR_EYES)).contains("on");

        List<EventEnvelope> events = turnOff(
                ctx,
                sign(ctx, Seat.FIRST, "sub-a", Duration.ofMinutes(10)),
                sign(ctx, Seat.SECOND, "sub-b", Duration.ofSeconds(5)));
        assertThat(events)
                .extracting(e -> e.event().getClass().getSimpleName())
                .containsExactly("ApprovalSigned", "ApprovalSigned", "SettingChanged");
        assertThat(((SettingChanged) events.getLast().event()).value()).isEqualTo("off");
        assertThat(f.company.preferences().setting(Setting.FOUR_EYES)).contains("off");
    }

    @Test
    void theSameSubjectCannotSignTwice() {
        turnOn();
        CommandContext ctx = f.director().withAssurance();
        refusedWith(
                Refusal.SAME_PERSON,
                () -> turnOff(
                        ctx,
                        sign(ctx, Seat.FIRST, "sub-a", Duration.ofSeconds(60)),
                        sign(ctx, Seat.SECOND, "sub-a", Duration.ofSeconds(5))));
        assertThat(f.company.preferences().setting(Setting.FOUR_EYES)).contains("on");
    }

    @Test
    void aFirstSignatureExpires() {
        turnOn();
        CommandContext ctx = f.director().withAssurance();
        refusedWith(
                Refusal.SIGNATURE_EXPIRED,
                () -> turnOff(
                        ctx,
                        sign(ctx, Seat.FIRST, "sub-a", AssurancePolicy.COUNTERSIGN_WINDOW.plusSeconds(5)),
                        sign(ctx, Seat.SECOND, "sub-b", Duration.ofSeconds(5))));
    }

    @Test
    void withoutAStrongerProofOrWhenAlreadyOffNothingIsAsked() {
        turnOn();
        // not assured: the click, as before IAM mode existed
        f.record(f.company.preferences().changeSetting(f.director(), Setting.FOUR_EYES, "off"));
        assertThat(f.company.preferences().setting(Setting.FOUR_EYES)).contains("off");
        // already off, assured: turning off what is off records nothing and asks nothing
        CommandContext ctx = f.director().withAssurance();
        assertThat(turnOff(ctx)).isEmpty();
    }

    @Test
    void anyOtherSettingKeepsTheClickInAnAssuredContext() {
        CommandContext ctx = f.director().withAssurance();
        f.record(f.company.preferences().changeSetting(ctx, Setting.PARALLEL_SESSIONS, "2"));
        assertThat(f.company.preferences().setting(Setting.PARALLEL_SESSIONS)).contains("2");
    }
}
