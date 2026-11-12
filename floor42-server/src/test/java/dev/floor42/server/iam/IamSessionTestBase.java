package dev.floor42.server.iam;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.app.MissionGovernor;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.SessionIdentity.Minted;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.HookRegistry;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A mission in progress, a Keycloak token for its session, and the journal to read: what IAM session tests share. */
public abstract class IamSessionTestBase {

    public static final AgentId CHRIS = new AgentId("chris");

    public record Setup(MissionId mission, TicketId ticket, String workdir) {}

    @Inject
    protected OfficeService office;

    @Inject
    protected HookRegistry hooks;

    @Inject
    protected AgentTokenVerifier verifier;

    @Inject
    protected Journal journal;

    @Inject
    protected IamStatus status;

    @Inject
    protected SecretVault vault;

    @Inject
    protected dev.floor42.server.runtime.Sandbox sandbox;

    /**
     * The sandbox on and taken as working (a profile may already say so; a profile that does not must not depend on the
     * host having WSL, which a GitHub Windows runner lacks), then IAM mode's start-up checks again: it is usable.
     */
    @org.junit.jupiter.api.BeforeEach
    void iamUsable() {
        dev.floor42.server.runtime.SandboxSeams.assume(sandbox, true);
        if (!IamOnProfile.fake.clients().contains("floor42-daemon")) {
            IamOnProfile.fake.registerClient("floor42-daemon", "daemon-secret");
            vault.put("floor42-daemon", "daemon-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!IamOnProfile.fake.clients().contains("floor42-api"))
            IamOnProfile.fake.registerClient("floor42-api", "api-secret-4567");
        if (vault.get("floor42-api").isEmpty())
            vault.put("floor42-api", "api-secret-4567".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        office.execute(c -> c.preferences().changeSetting(director(), dev.floor42.domain.Setting.SHELL_SANDBOX, "wsl"));
        status.start();
    }

    @org.junit.jupiter.api.AfterEach
    void sandboxMeasuredAgain() {
        dev.floor42.server.runtime.SandboxSeams.assume(sandbox, null);
    }

    public dev.floor42.domain.CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    public Setup realMissionInProgress() throws Exception {
        var workdir = Files.createTempDirectory("office-session");
        if (office.query(c -> c.staff().find(CHRIS).isEmpty())) {
            office.execute(c -> c.staff()
                    .hire(
                            director(),
                            CHRIS,
                            "Chris",
                            "Java developer",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of()));
        }
        MissionId m = ((MissionCreated) office.execute(c -> c.missions()
                                .create(
                                        director(),
                                        "Inspect",
                                        "Inspect the repository",
                                        workdir.toString(),
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(director(), m, "Inspect", "", Priority.NORMAL, List.of(), List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        office.execute(c -> c.missions().setToolPermission(director(), m, "Read", ActionCategory.READ, Decision.ALLOW));
        office.execute(c -> c.missions().submitPlan(director(), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), t));
        return new Setup(m, t, workdir.toString());
    }

    public SessionSpec spec(Setup s) {
        return new SessionSpec(
                s.mission(),
                s.ticket(),
                CHRIS,
                Origin.REAL,
                s.workdir(),
                "brief",
                "claude-sonnet-5-5",
                "medium",
                List.of("Read"));
    }

    /** A token as the issuer gives it for this launch and session (the exchange without the HTTP round trip). */
    public String tokenFor(Setup s, String launch) {
        return IamOnProfile.fake.exchangeForTest(
                "floor42-agent-chris",
                IamOnProfile.fake.directorToken("director"),
                Map.of(
                        "audience",
                        "floor42-api",
                        "floor42_launch",
                        launch,
                        "floor42_mission",
                        s.mission().value(),
                        "floor42_ticket",
                        s.ticket().value()));
    }

    /** The session registered with a live governor, the token returned; its process bound when {@code pid} is given. */
    public String liveSession(Setup s, String launch, Long pid) {
        String jwt = tokenFor(s, launch);
        Ok ok = (Ok) verifier.verify(jwt);
        Minted minted = new Minted(jwt, ok.jti(), ok.issuer(), ok.expiresAt());
        hooks.register(
                new MissionGovernor(office, spec(s)),
                new SessionClaims(launch, CHRIS, s.mission(), s.ticket()),
                minted);
        if (pid != null) hooks.bindProcess(jwt, pid);
        return jwt;
    }

    public String liveSession(Setup s, Long pid) {
        return liveSession(s, UUID.randomUUID().toString(), pid);
    }

    public static String preToolUse(String tool, String id) {
        return "{\"tool_name\":\"" + tool + "\",\"tool_use_id\":\"" + id
                + "\",\"tool_input\":{\"file_path\":\"a.txt\"}}";
    }

    /** Every journaled event as text, to look for what must be there and what must never be. */
    public String journalText() {
        return journal.readAll().stream().map(e -> e.event().toString()).reduce("", (a, b) -> a + "\n" + b);
    }
}
