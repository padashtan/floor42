package dev.floor42.server.runtime;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Registers a harmless sleeping process as an agent's running session, to see it stopped (no model, no Claude Code). */
public final class RunningSessionsProbe {

    private RunningSessionsProbe() {}

    public static Process sleeper(RunningSessions running, AgentId agent, MissionId mission) throws IOException {
        Path src = Files.createTempFile("Sleep", ".java");
        Files.writeString(
                src, "class Sleep { public static void main(String[] a) throws Exception { Thread.sleep(60000); } }");
        Process p = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(), src.toString())
                .start();
        running.started(agent, mission, p);
        return p;
    }
}
