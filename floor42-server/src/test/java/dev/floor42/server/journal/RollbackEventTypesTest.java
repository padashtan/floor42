package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rollback safety of 1.15.0 (ADR-0061): a journal 1.15 wrote, with no version 3 pin, has to replay on 1.14.1. The
 * event types 1.14.1 knows are kept here as it shipped them ({@code event-types-1.14.1.txt}); 1.15 adds exactly one,
 * {@code WorkflowStepTicketOpened}, which only a version 3 mission writes and which 1.14.1 would set aside
 * ({@code NEWER_VERSION}). No other type, field or journaled value changed: the enumerations the events carry
 * ({@code TicketKind}, {@code ReviewVerdict}, {@code Origin}) are unchanged, a task is a plain {@code WORK} ticket.
 */
class RollbackEventTypesTest {

    private static List<String> types(String resource) throws IOException {
        try (InputStream in = RollbackEventTypesTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }

    @Test
    void release115AddsTheOneLinkEventToWhatRelease1141Knows() throws IOException {
        List<String> before = types("/event-types-1.14.1.txt");
        List<String> now = types("/event-types-1.2.0.txt");
        assertThat(now).containsAll(before);
        assertThat(now.stream().filter(t -> !before.contains(t)))
                .containsExactly(
                        "WorkflowStepTicketOpened",
                        "NodePlanned",
                        "AgentTokenIssued",
                        "UnsafeModeChanged",
                        "BreakGlassSignIn",
                        "SessionRiskRaised",
                        "MissionQuarantined");
    }

    /**
     * 1.16 (ADR-0062): the graph missions add exactly one type to what 1.15.0 knew. Only a graph mission writes it, a
     * mission 1.15 cannot run at all (its version 4 pin is {@code Unrunnable} there), and 1.15 would set the row aside
     * ({@code NEWER_VERSION}), alone.
     */
    @Test
    void release116AddsTheOneNodeEventToWhatRelease1150Knows() throws IOException {
        List<String> before = types("/event-types-1.15.0.txt");
        List<String> now = types("/event-types-1.2.0.txt");
        assertThat(now).containsAll(before);
        assertThat(now.stream().filter(t -> !before.contains(t)))
                .containsExactly(
                        "NodePlanned",
                        "AgentTokenIssued",
                        "UnsafeModeChanged",
                        "BreakGlassSignIn",
                        "SessionRiskRaised",
                        "MissionQuarantined");
    }

    /**
     * 1.17: five types over 1.16.0. {@code AgentTokenIssued} (agent identity) and {@code BreakGlassSignIn} (the
     * console link used while IAM mode is on) are written only in IAM mode, off by default; {@code UnsafeModeChanged}
     * (ADR-0063, D16) only when the director switches unsafe mode; {@code SessionRiskRaised} (ADR-0066) only when a REAL session's risk goes up; {@code MissionQuarantined} only on the director's quarantine click. 1.16 would set any of these rows aside ({@code NEWER_VERSION}). No journaled enumeration changed.
     */
    @Test
    void release117AddsTheTokenTheUnsafeModeTheBreakGlassAndTheRiskEventsToWhatRelease1160Knows() throws IOException {
        List<String> before116 = types("/event-types-1.16.0.txt");
        List<String> now = types("/event-types-1.2.0.txt");
        assertThat(now).containsAll(before116);
        assertThat(now.stream().filter(t -> !before116.contains(t)))
                .containsExactly(
                        "AgentTokenIssued",
                        "UnsafeModeChanged",
                        "BreakGlassSignIn",
                        "SessionRiskRaised",
                        "MissionQuarantined");
    }

    /**
     * 1.3.0 (ADR-0080, ADR-0081, ADR-0082): three types over 1.2.0. {@code AuthorizationChanged} is written only in IAM
     * mode, when a grant, a delegation or an MCP tick changes, and by the one-shot import of grants.json;
     * {@code ApprovalSigned} records each signature of an assured act; {@code AgentTrustChanged} is written when an
     * incident narrows an agent's level, a real hire starts one, or the director raises or lowers it. 1.2.0 sets these
     * rows aside ({@code NEWER_VERSION}) and applies the director's permission table without the trust cap: it falls
     * back to what the director approved, never beyond it. No journaled enumeration changed.
     */
    @Test
    void release130AddsItsThreeEventsToWhatRelease120Knows() throws IOException {
        List<String> before = types("/event-types-1.2.0.txt");
        List<String> now = types("/event-types-1.3.1.txt");
        assertThat(now).containsAll(before);
        assertThat(now.stream().filter(t -> !before.contains(t)))
                .containsExactlyInAnyOrder("AuthorizationChanged", "ApprovalSigned", "AgentTrustChanged");
    }

    /**
     * 1.4.0 (ADR-0084): two types over 1.3.1. {@code DeliveryEvidenceRecorded} is written when the director reads the
     * checks of a pull request's head commit, {@code PullRequestMerged} on the proven click that merges it. 1.3.1 sets
     * these rows aside ({@code NEWER_VERSION}). No journaled enumeration changed.
     */
    @Test
    void release140AddsTheEvidenceAndTheMergeEventsToWhatRelease131Knows() throws IOException {
        List<String> before = types("/event-types-1.3.1.txt");
        List<String> now = types("/dev/floor42/server/journal/event-types.txt");
        assertThat(now).containsAll(before);
        assertThat(now.stream().filter(t -> !before.contains(t)))
                .containsExactlyInAnyOrder("DeliveryEvidenceRecorded", "PullRequestMerged");
    }

    @Test
    void theJournaledEnumerationsAreTheOnesOfRelease1141() {
        assertThat(dev.floor42.domain.TicketKind.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("WORK", "PLANNING", "MEETING");
        assertThat(dev.floor42.domain.ReviewVerdict.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("VALIDATED", "VALIDATED_WITH_RESERVATIONS", "REJECTED", "UNVERIFIABLE");
        assertThat(dev.floor42.domain.DenialReason.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "POLICY", "DIRECTOR", "MISSION_NOT_ACTIVE", "OUTSIDE_WORKSPACE", "BLOCKED_UPSTREAM");
    }
}
