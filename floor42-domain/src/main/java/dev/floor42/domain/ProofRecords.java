package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the journal holds about a delivery's proof (ADR-0058), as the screens and the acceptance read it. */
public final class ProofRecords {

    private ProofRecords() {}

    /** Floor42's last run of one worktree's recipe. */
    public record Run(ProofTarget target, List<ProofStep> steps, Instant at) {
        public Run {
            steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
        }

        public boolean failed() {
            return steps.stream().anyMatch(ProofStep::failed);
        }
    }

    /** What the director saw running one of their steps. */
    public record Witness(ProofTarget target, int step, boolean works, String seen, Instant at) {}

    /** The director's verdict, on the targets as they were when given. */
    public record Verdict(String verdict, List<ProofTarget> targets, String note, Instant at) {
        public Verdict {
            targets = targets == null ? null : Collections.unmodifiableList(new ArrayList<>(targets));
        }

        public boolean proven() {
            return "PROVEN".equals(verdict);
        }
    }
}
