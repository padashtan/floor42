package dev.floor42.server.app.mission;

import dev.floor42.domain.MissionId;
import dev.floor42.server.security.LogSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/** The accept commit's git command, signed when the agent has a key and made again unsigned when signing fails. */
final class WorkshopCommit {

    private static final Logger LOG = Logger.getLogger(WorkshopCommit.class);

    private WorkshopCommit() {}

    static String[] args(List<String> identity, List<String> signingArgs, MissionId mission, String message) {
        List<String> commit = new ArrayList<>(identity);
        commit.addAll(signingArgs);
        commit.addAll(List.of(
                "commit", "--quiet", "-m", message, "-m", "Accepted by the director in Floor42 (" + mission + ")."));
        return commit.toArray(String[]::new);
    }

    /**
     * Runs the commit. When the signed one fails (git before 2.34, an ssh-keygen that refuses the key, a key retired
     * meanwhile) it is made once more without the signing arguments: unsigned, said in the log and in the dossier,
     * rather than a patch lost.
     */
    static void run(
            Consumer<String[]> git,
            List<String> identity,
            List<String> signingArgs,
            MissionId mission,
            String message,
            String where) {
        try {
            git.accept(args(identity, signingArgs, mission, message));
        } catch (RuntimeException e) {
            if (signingArgs.isEmpty()) throw e;
            LOG.warnf(
                    "%s: the signed commit failed (%s), committing without a signature",
                    LogSafe.of(where), LogSafe.of(e.getMessage()));
            git.accept(args(identity, List.of(), mission, message));
        }
    }
}
