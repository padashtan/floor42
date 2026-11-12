package dev.floor42.server.app;

import dev.floor42.domain.Setting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The settings in force: what the director set from the settings panel (journaled), else the daemon's configuration.
 * Read each time they are needed, so a change applies to the next session without a restart.
 */
@ApplicationScoped
public class OfficeSettings
        implements dev.floor42.server.runtime.CostCap,
                dev.floor42.server.runtime.SandboxPolicy,
                dev.floor42.server.runtime.AcceptedLines {

    @Inject
    OfficeService office;

    @ConfigProperty(name = "floor42.parallel.max-sessions", defaultValue = "2")
    int parallelSessions;

    @ConfigProperty(name = "floor42.plan-limit.threshold", defaultValue = "0.9")
    double planLimitThreshold;

    @ConfigProperty(name = "floor42.retro.model", defaultValue = "claude-haiku-5-5")
    String retroModel;

    @ConfigProperty(name = "floor42.session.budget-usd", defaultValue = "2.0")
    double sessionBudgetUsd;

    /** Off only where the fake Claude Code runs (tests, dev mode): a real mission must have the sandbox (ADR-0063). */
    @ConfigProperty(name = "floor42.sandbox.mandatory", defaultValue = "true")
    boolean sandboxMandatory;

    /**
     * Whether the commands of a session run in the sandbox: the director's setting, or the mandatory sandbox of a real
     * mission outside unsafe mode (ADR-0063). The same rule as {@link #sandboxEnabled()} and {@link #sandboxRequired()},
     * for the code that holds the company and not the settings bean (briefs, questions, permission views).
     */
    public static boolean sandboxed(dev.floor42.domain.Company c) {
        return sandboxed(c, mandatoryConfigured());
    }

    static boolean sandboxed(dev.floor42.domain.Company c, boolean mandatory) {
        var prefs = c.preferences();
        boolean setting = "wsl".equals(prefs.setting(Setting.SHELL_SANDBOX).orElse("off"));
        return setting || (mandatory && !prefs.unsafeMode());
    }

    private static Boolean mandatoryPinned;

    /** For tests: the mandatory flag the helper reads (the plain-JUnit config has no test profile); null re-reads it. */
    static synchronized void pinMandatoryForTests(Boolean value) {
        mandatoryPinned = value;
    }

    private static synchronized boolean mandatoryConfigured() {
        if (mandatoryPinned == null) {
            mandatoryPinned = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("floor42.sandbox.mandatory", Boolean.class)
                    .orElse(true);
        }
        return mandatoryPinned;
    }

    /** What Floor42 knows of the director until they describe themselves: change it in the settings. */
    static final String DIRECTOR_PROFILE =
            "A technical lead who prefers small, reviewed changes and decisions backed by measured facts.";

    /** The daemon's own value, before any change by the director. */
    public String defaultOf(Setting setting) {
        return switch (setting) {
            case PARALLEL_SESSIONS -> Integer.toString(parallelSessions);
            case PLAN_LIMIT_THRESHOLD -> Double.toString(planLimitThreshold);
            case RETRO_MODEL -> retroModel;
            // Agents write in English unless the director chooses otherwise: the code and commits are in English.
            case AGENT_LANGUAGE -> "en";
            case DIRECTOR_PROFILE -> DIRECTOR_PROFILE;
            case SESSION_BUDGET_USD -> Double.toString(sessionBudgetUsd);
            case OFFICE_THEME -> "1990s";
            // Off until the director turns it on: the sandbox needs WSL2, and the tools the commands use installed in
            // it.
            case SHELL_SANDBOX -> "off";
            case SANDBOX_NETWORK -> "off";
            case ACCEPTED_STREAM_LINES -> "";
            case STALL_MINUTES -> Integer.toString(stallMinutes);
            case MISSION_BUDGET_USD -> missionBudgetUsd;
        };
    }

    /** What a plan's approval offers as the mission's cap, until the director changes it: an amount, or none. */
    @ConfigProperty(name = "floor42.mission.default-budget-usd", defaultValue = "5.0")
    String missionBudgetUsd;

    @ConfigProperty(name = "floor42.stall.minutes", defaultValue = "5")
    int stallMinutes;

    /** How long work the dispatcher would start may wait, nothing running, before the mission is reported stuck. */
    public int stallMinutes() {
        return Integer.parseInt(valueOf(Setting.STALL_MINUTES));
    }

    public String valueOf(Setting setting) {
        return office.query(c -> c.preferences().setting(setting)).orElseGet(() -> defaultOf(setting));
    }

    public int parallelSessions() {
        return Integer.parseInt(valueOf(Setting.PARALLEL_SESSIONS));
    }

    public double planLimitThreshold() {
        return Double.parseDouble(valueOf(Setting.PLAN_LIMIT_THRESHOLD));
    }

    public String retroModel() {
        return valueOf(Setting.RETRO_MODEL);
    }

    public double sessionBudgetUsd() {
        return Double.parseDouble(valueOf(Setting.SESSION_BUDGET_USD));
    }

    /**
     * The cap one session of this mission runs under: the session cap, or what is left of the mission's budget when
     * that is less. A mission with nothing left gets no session at all (the budget hold), so the cap is never zero.
     */
    @Override
    public double sessionCapUsd(dev.floor42.domain.MissionId mission) {
        double cap = sessionBudgetUsd();
        if (mission == null) return cap;
        double left = office.query(c -> c.verification().budgetLeftUsd(mission)).orElse(cap);
        return Math.max(Setting.SESSION_BUDGET_MIN, Math.min(cap, left));
    }

    @Override
    public boolean sandboxEnabled() {
        return "wsl".equals(valueOf(Setting.SHELL_SANDBOX));
    }

    /** Unsafe mode: the director's journaled choice to let a REAL mission's shell run without the sandbox. */
    public boolean unsafeMode() {
        return office.query(c -> c.preferences().unsafeMode());
    }

    @Override
    public boolean sandboxRequired() {
        return sandboxMandatory && !unsafeMode();
    }

    @Override
    public boolean sandboxNetwork() {
        return "on".equals(valueOf(Setting.SANDBOX_NETWORK));
    }

    @Override
    public java.util.Set<String> acceptedStreamLines() {
        String v = valueOf(Setting.ACCEPTED_STREAM_LINES);
        return v.isBlank() ? java.util.Set.of() : java.util.Set.of(v.split(","));
    }

    public String directorProfile() {
        return valueOf(Setting.DIRECTOR_PROFILE);
    }
}
