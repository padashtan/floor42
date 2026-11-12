package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One trace of the workshop (spec section 11.3): who did what, on which files, when, with what effect.
 *
 * @param workspace nullable for a directive (it names a ticket, not a workspace)
 * @param ticket nullable except for a directive
 * @param subject the file, the directive, the commit message or the rejection reason
 * @param detail the diff, the commit id or the archive; nullable
 */
public record Intervention(
        Kind kind,
        MissionId mission,
        Instant at,
        Actor author,
        AgentId workspace,
        TicketId ticket,
        String subject,
        String detail,
        List<String> files) {
    public Intervention {
        files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
    }

    public enum Kind {
        DIRECTOR_EDIT,
        DIRECTIVE,
        PATCH_ACCEPTED,
        PATCH_REJECTED
    }
}
