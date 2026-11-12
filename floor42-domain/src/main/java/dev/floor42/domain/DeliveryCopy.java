package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One file of a mission copied, or not, into the destination the director chose (ADR-0051), as Floor42 measured it on
 * disk.
 *
 * @param agent whose branch or worktree the file came from
 * @param path its path in that branch or worktree
 * @param writtenAs its path under the mission's folder of the destination; null when nothing was written
 * @param outcome {@code WRITTEN}, {@code KEPT_BOTH} (the name was taken by other bytes: written beside it), {@code SAME}
 *     (the same bytes were there: nothing written) or {@code LEFT_OUT}
 * @param reason why it was left out; null otherwise
 */
public record DeliveryCopy(String agent, String path, String writtenAs, String outcome, String reason) {

    public static final String WRITTEN = "WRITTEN";
    public static final String KEPT_BOTH = "KEPT_BOTH";
    public static final String SAME = "SAME";
    public static final String LEFT_OUT = "LEFT_OUT";

    static final Set<String> OUTCOMES = Set.of(WRITTEN, KEPT_BOTH, SAME, LEFT_OUT);

    /** At most this many files in one journaled copy; the rest is counted in {@link Done#leftOut}. */
    public static final int MAX_FILES = 1000;

    /**
     * A copy as journaled: the folder written (the destination's folder for this mission), each file's outcome, how
     * many more were left out unlisted, and whether a listing of the mission was cut at its cap.
     */
    public record Done(String folder, List<DeliveryCopy> files, int leftOut, boolean truncated, Instant at) {
        public Done {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }
}
