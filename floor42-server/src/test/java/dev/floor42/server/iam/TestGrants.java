package dev.floor42.server.iam;

import dev.floor42.domain.AuthorizationTuple;
import dev.floor42.server.iam.OfficeFacts.AuthorizationChange;
import dev.floor42.server.iam.fga.EvaluatingFga;
import dev.floor42.server.iam.fga.GrantTuples;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** {@link GrantTuples} over an in-memory OpenFGA, with a journal that keeps its rows in a list: for the unit tests. */
public final class TestGrants {

    /** The rows the stand-in journal received. */
    public final List<AuthorizationChange> rows = new ArrayList<>();

    public final EvaluatingFga fga;
    public final GrantTuples tuples;
    private final Set<AuthorizationTuple> standing = new LinkedHashSet<>();
    private boolean imported;
    /** While true the stand-in journal refuses every row, as a journal that cannot be written does. */
    public volatile boolean failJournal;

    public TestGrants() {
        this(new EvaluatingFga(), Optional.of("01TESTMODEL"));
    }

    public TestGrants(EvaluatingFga fga, Optional<String> model) {
        this.fga = fga;
        this.tuples = new GrantTuples(fga, () -> model, new OfficeFacts() {
            @Override
            public java.util.Collection<dev.floor42.domain.AgentId> activeStaff() {
                return List.of();
            }

            @Override
            public Optional<dev.floor42.domain.MissionStatus> missionStatus(dev.floor42.domain.MissionId mission) {
                return Optional.empty();
            }

            @Override
            public boolean isRetroTicket(dev.floor42.domain.MissionId mission, dev.floor42.domain.TicketId ticket) {
                return false;
            }

            @Override
            public void breakGlassSignIn(String session) {}

            @Override
            public void authorizationChanged(AuthorizationChange change) {
                if (failJournal) throw new IllegalStateException("the journal cannot be written (test)");
                imported |= change.imported();
                rows.add(change);
                standing.addAll(change.writes());
                standing.removeAll(change.deletes());
            }

            @Override
            public boolean authorizationImported() {
                return imported;
            }

            @Override
            public Set<AuthorizationTuple> journaledAuthorization() {
                return new LinkedHashSet<>(standing);
            }
        });
    }
}
