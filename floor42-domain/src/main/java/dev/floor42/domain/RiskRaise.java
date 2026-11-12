package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The risk went up to {@code level}. Reasons in first-seen order, actions the latest kept. No secret: codes and ids. */
public record RiskRaise(RiskLevel level, List<RiskReason> reasons, Instant windowStart, List<ActionId> actions) {
    public RiskRaise {
        actions = actions == null ? null : Collections.unmodifiableList(new ArrayList<>(actions));
        reasons = reasons == null ? null : Collections.unmodifiableList(new ArrayList<>(reasons));
    }
}
