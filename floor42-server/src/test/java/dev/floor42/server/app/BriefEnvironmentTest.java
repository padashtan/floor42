package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Setting;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.PreferenceEvent.SettingChanged;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Every developer and reviewer brief says where its tools run, so the agent does not guess a path form or a tool. */
class BriefEnvironmentTest {

    @AfterEach
    void unpin() {
        BriefEnvironment.pinHostForTests(null);
        OfficeSettings.pinMandatoryForTests(null);
    }

    @Test
    void theMandatorySandboxCountsEvenWithTheSettingOffUnlessUnsafeModeIsOn() {
        Company off = without(Setting.SHELL_SANDBOX);
        assertThat(OfficeSettings.sandboxed(off, true)).isTrue();
        assertThat(OfficeSettings.sandboxed(off, false)).isFalse();
        List<EventEnvelope> unsafe = withSetting(BriefVariants.journal(), Setting.SHELL_SANDBOX);
        unsafe.add(new EventEnvelope(
                "unsafe",
                Instant.parse("2026-10-21T08:01:00Z"),
                Origin.REAL,
                Actor.director(),
                null,
                null,
                null,
                new dev.floor42.domain.event.PreferenceEvent.UnsafeModeChanged(true, "test")));
        assertThat(OfficeSettings.sandboxed(Company.replay(unsafe), true)).isFalse();
        // The setting alone still turns it on.
        assertThat(OfficeSettings.sandboxed(Company.replay(BriefVariants.journal()), false))
                .isTrue();
        BriefEnvironment.pinHostForTests("Windows 11");
        OfficeSettings.pinMandatoryForTests(true);
        assertThat(producer(off)).contains("Sandbox: on. Network in the sandbox:");
    }

    @Test
    void inTheSandboxTheBriefsNameBothPathFormsTheMeasuredSystemAndTheNetwork() {
        BriefEnvironment.pinHostForTests("Windows 11");
        Company c = Company.replay(BriefVariants.journal());

        String producer = producer(c);
        assertThat(producer)
                .contains("Execution environment (stated by Floor42")
                .contains("file tools (Read, Glob, Grep, Write, Edit) run on Windows")
                .contains("A Linux path such as /media/work/... or /mnt/c/... is not a path there")
                .contains("Bash runs in a Linux sandbox (Ubuntu 24.04)")
                .contains("your working directory is /media/work")
                .contains("Sandbox: on. Network in the sandbox: open")
                .contains("BLOCKED BY ENVIRONMENT: <what is missing>; step: <the step it prevents>");
        assertThat(reviewer(c))
                .contains("Execution environment (stated by Floor42")
                .contains("Bash runs in a Linux sandbox (Ubuntu 24.04)")
                .contains("do not answer REJECTED for that step alone")
                .contains("write the line ENVIRONMENT ONLY: yes before your verdict");
    }

    @Test
    void aLinuxHostWithTheSandboxDoesNotClaimToRunOnWindows() {
        BriefEnvironment.pinHostForTests("Linux");
        String producer = producer(Company.replay(BriefVariants.journal()));
        assertThat(producer)
                .contains("file tools (Read, Glob, Grep, Write, Edit) run on Linux")
                .contains("Bash runs in a Linux sandbox")
                .doesNotContain("run on Windows");
    }

    @Test
    void theProducerAndThePlannerReadWhichEssentialToolsTheSandboxHasAndLacks() {
        BriefEnvironment.pinHostForTests("Windows 11");
        List<EventEnvelope> events = new ArrayList<>(BriefVariants.journal());
        events.add(new EventEnvelope(
                "probe",
                Instant.parse("2026-10-21T08:00:00Z"),
                Origin.REAL,
                Actor.system(),
                null,
                null,
                null,
                new dev.floor42.domain.event.PreferenceEvent.SandboxToolsProbed(
                        "Debian",
                        "debian",
                        null,
                        "13",
                        "Debian GNU/Linux 13 (trixie)",
                        true,
                        List.of(
                                "git|/usr/bin/git|git version 2.47.3",
                                "python3|/usr/bin/python3|Python 3.13.5",
                                "chromium||",
                                "chromium-browser||",
                                "google-chrome||",
                                "google-chrome-stable||",
                                "pdftotext||"),
                        null)));
        Company c = Company.replay(events);

        assertThat(producer(c))
                .contains("- Tools in the sandbox, measured by Floor42 on 2026-10-21T08:00:00Z: present git, python3;"
                        + " absent chromium, pdftotext; not measured node, java, make, pandoc, libreoffice.");
        String planner = Briefs.planner(
                c,
                c.missions().find(BriefVariants.M).orElseThrow(),
                null,
                c.staff().find(new AgentId("bob")).orElseThrow());
        assertThat(planner)
                .contains("NEEDS: <the programs its commands must run")
                .contains("absent chromium, pdftotext")
                .contains("Floor42 asks the director about a ticket that NEEDS an absent tool");

        Company off = Company.replay(withSetting(events, Setting.SHELL_SANDBOX));
        assertThat(Briefs.planner(
                        off,
                        off.missions().find(BriefVariants.M).orElseThrow(),
                        null,
                        off.staff().find(new AgentId("bob")).orElseThrow()))
                .as("without the sandbox the tools are the machine's own: nothing measured to state")
                .contains("NEEDS:")
                .doesNotContain("Tools in the sandbox");
    }

    @Test
    void withoutTheSandboxOnWindowsTheBriefSaysSo() {
        BriefEnvironment.pinHostForTests("Windows 11");
        Company c = without(Setting.SHELL_SANDBOX);

        assertThat(producer(c))
                .contains("Floor42 runs on Windows, with no sandbox")
                .contains("Git Bash")
                .contains("Sandbox: off")
                .doesNotContain("/media/work");
    }

    @Test
    void onAnotherSystemThePathsAreTheSame() {
        BriefEnvironment.pinHostForTests("Linux");
        Company c = without(Setting.SHELL_SANDBOX);

        assertThat(reviewer(c))
                .contains("Floor42 runs on Linux, with no sandbox")
                .contains("with the same paths");
    }

    private static String producer(Company c) {
        return Briefs.producer(
                c,
                c.missions().find(BriefVariants.M).orElseThrow(),
                c.tickets().find(new TicketId("T-2")).orElseThrow(),
                c.staff().find(new AgentId("bob")).orElseThrow());
    }

    private static String reviewer(Company c) {
        return Briefs.reviewer(
                c,
                c.missions().find(BriefVariants.M).orElseThrow(),
                c.tickets().find(new TicketId("T-2")).orElseThrow(),
                c.staff().find(new AgentId("ada")).orElseThrow());
    }

    private static Company without(Setting setting) {
        return Company.replay(withSetting(BriefVariants.journal(), setting));
    }

    private static List<EventEnvelope> withSetting(List<EventEnvelope> base, Setting setting) {
        List<EventEnvelope> events = new ArrayList<>(base);
        events.add(new EventEnvelope(
                "off",
                Instant.parse("2026-10-21T08:00:00Z"),
                Origin.REAL,
                Actor.director(),
                null,
                null,
                null,
                new SettingChanged(setting, "off")));
        return events;
    }
}
