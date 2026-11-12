package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.domain.RiskLevel;
import dev.floor42.domain.RiskReason;
import dev.floor42.server.iam.IamSessionProfile;
import dev.floor42.server.iam.IamSessionTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A session at ALERT or above is a SESSION_RISK item until the mission is suspended after it (ADR-0066). */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class ProgressRiskTest extends IamSessionTestBase {

    @Inject
    Progress progress;

    private String risk(Setup s, RiskLevel level) {
        var ctx = office.context(Actor.system(), Origin.REAL);
        String id = "risk-" + UUID.randomUUID();
        office.execute(c -> c.sessions().start(ctx, id, s.ticket(), CHRIS, "claude-sonnet-5-5", "2.1.304"));
        office.execute(c -> c.sessions()
                .riskRaised(ctx, id, level, List.of(RiskReason.OUTSIDE_WORKSPACE), Instant.now(), List.of()));
        return id;
    }

    private List<Progress.Item> items(Setup s) {
        return progress.of(s.mission()).stuck().stream()
                .filter(i -> "SESSION_RISK".equals(i.kind()))
                .toList();
    }

    @Test
    void anAlertShowsAsARiskItem() throws Exception {
        Setup s = realMissionInProgress();
        risk(s, RiskLevel.ALERT);
        assertThat(items(s)).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo("ALERT");
            assertThat(i.text()).isEqualTo("OUTSIDE_WORKSPACE");
            assertThat(i.agent()).isEqualTo("chris");
            assertThat(i.ticket()).isEqualTo(s.ticket().value());
        });
    }

    @Test
    void anAlertStaysWhenTheAgentsNextSessionStartsAndEveryRaisedSessionShowsMostSevereFirst() throws Exception {
        Setup s = realMissionInProgress();
        var ctx = office.context(Actor.system(), Origin.REAL);
        String first = risk(s, RiskLevel.ALERT);
        office.execute(c -> c.sessions().end(ctx, first, false, "completed", 1, 1L, 1L, 0.0));
        office.execute(c -> c.sessions().start(ctx, "next-session", s.ticket(), CHRIS, "claude-sonnet-5-5", "2.1.304"));
        assertThat(items(s))
                .singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo("ALERT"));
        office.execute(c -> c.sessions()
                .riskRaised(
                        ctx,
                        "next-session",
                        RiskLevel.QUARANTINE,
                        List.of(RiskReason.OWN_LEASH),
                        Instant.now(),
                        List.of()));
        assertThat(items(s)).extracting(Progress.Item::status).containsExactly("QUARANTINE", "ALERT");
    }

    @Test
    void aNoticeShowsNothing() throws Exception {
        Setup s = realMissionInProgress();
        risk(s, RiskLevel.NOTICE);
        assertThat(items(s)).isEmpty();
    }

    @Test
    void aSuspensionAfterTheRaiseClearsTheItem() throws Exception {
        Setup s = realMissionInProgress();
        risk(s, RiskLevel.QUARANTINE);
        assertThat(items(s)).hasSize(1);
        office.execute(c -> c.missions().suspend(director(), s.mission(), "test"));
        assertThat(items(s)).isEmpty();
    }
}
