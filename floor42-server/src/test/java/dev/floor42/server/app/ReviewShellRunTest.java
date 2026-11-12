package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.server.app.StalledMissions.Stalled;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.CapturingRuntime;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import dev.floor42.server.runtime.Sandbox;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ReviewRunner end to end up to the session (ADR-0046, amended 2026-11-05): a real review of a ticket, the session
 * captured instead of started. The shell is given only with the sandbox on and working and no refusal of the director's
 * on Bash; the line that opens it is the reviewer's, on that ticket, and lasts as long as the session.
 */
@QuarkusTest
@TestProfile(StallTest.DispatcherOff.class)
class ReviewShellRunTest {

    static final AgentId REVIEWER = SimulatedCrew.DEHREN;

    @Inject
    OfficeService office;

    @Inject
    SimulatedCrew crew;

    @Inject
    ReviewRunner reviews;

    @Inject
    MissionPermissions permissions;

    @Inject
    ObjectMapper json;

    CapturingRuntime runtime;
    final List<List<PermissionRule>> rulesDuring = new ArrayList<>();
    Stalled review;

    @BeforeEach
    void aTicketInReviewAndASandboxThatWorks() {
        QuarkusMock.installMockForType(sandbox(true, true), Sandbox.class);
        review = inReview();
        runtime = new CapturingRuntime(json, spec -> rulesDuring.add(rules(review.mission())));
        QuarkusMock.installMockForType(runtime, ClaudeCodeRuntime.class);
    }

    @Test
    void theReviewerGetsABashLineOnItsTicketAndAReadOnlyShellThatEndsWithTheSession() {
        // Floor42's own default line for Bash (ask) is not the director's refusal.
        office.execute(c -> c.missions()
                .setToolPermission(system(), review.mission(), "Bash", ActionCategory.EXECUTE, Decision.ASK));

        reviews.review(review.mission(), review.ticket(), REVIEWER);

        SessionSpec spec = runtime.specs.getFirst();
        assertThat(spec.readOnlyShell()).isTrue();
        assertThat(spec.tools()).contains("Bash").doesNotContain("Write", "Edit", "NotebookEdit", "PowerShell");
        assertThat(spec.brief()).contains("Review shell").contains("network is cut");
        assertThat(rulesDuring.getFirst()).contains(shellRule());
        assertThat(rules(review.mission())).as("removed after the session").doesNotContain(shellRule());
    }

    @ParameterizedTest
    @EnumSource(
            value = Decision.class,
            names = {"DENY", "ASK", "ASK_ALWAYS"})
    void aLineOfTheDirectorsOnBashTakesTheShellAway(Decision decision) {
        office.execute(c ->
                c.missions().setToolPermission(director(), review.mission(), "Bash", ActionCategory.EXECUTE, decision));

        reviews.review(review.mission(), review.ticket(), REVIEWER);

        SessionSpec spec = runtime.specs.getFirst();
        assertThat(spec.readOnlyShell()).isFalse();
        assertThat(spec.tools()).doesNotContain("Bash");
        assertThat(rulesDuring.getFirst()).doesNotContain(shellRule());
        assertThat(spec.brief()).doesNotContain("Review shell");
    }

    @Test
    void theDirectorsRuleOnTheReviewerTakesTheShellAwayToo() {
        office.execute(c -> c.missions()
                .addPermission(
                        director(),
                        review.mission(),
                        new PermissionRule(REVIEWER, ActionCategory.EXECUTE, "Bash", null, Decision.DENY, null)));

        reviews.review(review.mission(), review.ticket(), REVIEWER);

        assertThat(runtime.specs.getFirst().readOnlyShell()).isFalse();
    }

    @Test
    void withoutASandboxThereIsNoShellAndNoLine() {
        QuarkusMock.installMockForType(sandbox(true, false), Sandbox.class);

        reviews.review(review.mission(), review.ticket(), REVIEWER);

        assertThat(runtime.specs.getFirst().readOnlyShell()).isFalse();
        assertThat(runtime.specs.getFirst().tools()).doesNotContain("Bash");
        assertThat(rulesDuring.getFirst()).doesNotContain(shellRule());
    }

    @Test
    void anIdenticalLineOfTheDirectorsIsNotRemovedWithTheSession() {
        office.execute(c -> c.missions().addPermission(director(), review.mission(), shellRule()));

        reviews.review(review.mission(), review.ticket(), REVIEWER);

        assertThat(runtime.specs.getFirst().readOnlyShell()).isTrue();
        assertThat(rules(review.mission())).contains(shellRule());
    }

    @Test
    void aLineLeftByADaemonThatStoppedMidReviewIsSweptAtStartButNotTheDirectors() {
        PermissionRule left = shellRule();
        office.execute(c -> c.missions().addPermission(system(), review.mission(), left));
        Stalled other = inReview();
        PermissionRule theirs =
                new PermissionRule(REVIEWER, ActionCategory.EXECUTE, "Bash", null, Decision.ALLOW, other.ticket());
        office.execute(c -> c.missions().addPermission(director(), other.mission(), theirs));

        assertThat(permissions.dropLeftoverReviewShells()).isPositive();

        assertThat(rules(review.mission())).doesNotContain(left);
        assertThat(rules(other.mission())).as("the director's own line stays").contains(theirs);
    }

    private PermissionRule shellRule() {
        return new PermissionRule(REVIEWER, ActionCategory.EXECUTE, "Bash", null, Decision.ALLOW, review.ticket());
    }

    private List<PermissionRule> rules(MissionId m) {
        return office.query(
                c -> List.copyOf(c.missions().find(m).orElseThrow().policy().rules()));
    }

    private Stalled inReview() {
        try {
            Stalled s =
                    StalledMissions.of(office, crew, StallTest.gitRepository().toString(), Origin.REAL);
            office.execute(c -> c.tickets()
                    .submitForReview(
                            office.context(Actor.agent(SimulatedCrew.CHRIS), Origin.REAL), s.ticket(), "done"));
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    private CommandContext system() {
        return office.context(Actor.system(), Origin.REAL);
    }

    private static Sandbox sandbox(boolean enabled, boolean available) {
        return new Sandbox() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public boolean available() {
                return available;
            }
        };
    }
}
