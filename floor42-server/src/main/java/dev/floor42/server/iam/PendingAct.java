package dev.floor42.server.iam;

import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import java.time.Instant;

/** One act waiting for its signatures (ADR-0081); guarded by the lock of {@link AssuredActs}. */
final class PendingAct {
    final String id;
    final Act act;
    final boolean fourEyes;
    final String browserSession;
    final String directorSub;
    final Instant at;
    final AssuredActs.Execution run;
    ApprovalSigned first;
    Instant firstAt;

    PendingAct(
            String id,
            Act act,
            boolean fourEyes,
            String browserSession,
            String directorSub,
            Instant at,
            AssuredActs.Execution run) {
        this.id = id;
        this.act = act;
        this.fourEyes = fourEyes;
        this.browserSession = browserSession;
        this.directorSub = directorSub;
        this.at = at;
        this.run = run;
    }
}
