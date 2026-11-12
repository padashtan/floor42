package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayRuntimeTest {

    static List<String> transcript(String name) throws IOException {
        try (InputStream in = ReplayRuntimeTest.class.getResourceAsStream("/transcripts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    /** Records what the replay reports, allowing everything. */
    static final class AllowAll implements Governor {
        final List<String> completed = new ArrayList<>();
        final List<String> unreplayable = new ArrayList<>();

        @Override
        public Verdict decide(ToolRequested call) {
            return Verdict.ALLOW;
        }

        @Override
        public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
            return null;
        }

        @Override
        public void completed(ToolCompleted result) {
            completed.add(result.toolUseId());
        }

        @Override
        public void unreplayable(String toolUseId, String reason) {
            unreplayable.add(toolUseId);
        }
    }

    final SessionSpec spec =
            new SessionSpec(new MissionId("M-1"), new TicketId("T-1"), new AgentId("chris"), Origin.SIMULATED, "/w");

    @Test
    void aCallTheRecordingWasRefusedIsReportedAsUnreplayableNotAsItsOldRefusal() throws IOException {
        AllowAll governor = new AllowAll();

        SessionReport report = new ReplayRuntime(
                        transcript("readonly-inventory.jsonl"), new com.fasterxml.jackson.databind.ObjectMapper())
                .run(spec, governor);

        assertThat(governor.completed).hasSize(2);
        assertThat(governor.unreplayable).hasSize(1);
        assertThat(report.notes()).singleElement().asString().contains("no result to replay");
    }

    @Test
    void callsTheRecordingReallyRanHaveTheirResultsApplied() throws IOException {
        AllowAll governor = new AllowAll();

        new ReplayRuntime(transcript("inventory-with-notes.jsonl"), new com.fasterxml.jackson.databind.ObjectMapper())
                .run(spec, governor);

        assertThat(governor.completed).hasSize(4);
        assertThat(governor.unreplayable).isEmpty();
    }
}
