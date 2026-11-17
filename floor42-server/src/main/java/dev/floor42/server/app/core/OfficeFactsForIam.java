package dev.floor42.server.app.core;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AuthorizationTuple;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.OfficeFacts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/** The application's answer to the questions identity asks of the company (the port is owned by iam). */
@ApplicationScoped
class OfficeFactsForIam implements OfficeFacts {

    private final OfficeService office;

    @Inject
    OfficeFactsForIam(OfficeService office) {
        this.office = office;
    }

    @Override
    public Collection<AgentId> activeStaff() {
        return office.query(c -> c.staff().all().stream()
                .filter(a -> !a.dismissed())
                .map(a -> a.id())
                .toList());
    }

    @Override
    public Optional<MissionStatus> missionStatus(MissionId mission) {
        return office.query(c -> c.missions().find(mission).map(m -> m.status()));
    }

    @Override
    public void breakGlassSignIn(String session) {
        office.execute(c -> c.preferences()
                .recordBreakGlassSignIn(
                        office.context(dev.floor42.domain.Actor.director(), dev.floor42.domain.Origin.REAL), session));
    }

    @Override
    public void authorizationChanged(AuthorizationChange c) {
        // A grant and a tick are the director's own clicks; a delegation, an import and a reconcile are Floor42's, for
        // them
        Actor who = c.reason().equals("GRANT") || c.reason().equals("MCP_TICK") ? Actor.director() : Actor.system();
        office.execute(x -> x.preferences()
                .changeAuthorization(
                        office.context(who, Origin.REAL),
                        c.writes(),
                        c.deletes(),
                        c.modelId(),
                        c.reason(),
                        c.imported(),
                        c.reconciled()));
    }

    @Override
    public Set<AuthorizationTuple> journaledAuthorization() {
        return office.query(c -> c.preferences().authorization());
    }

    @Override
    public boolean authorizationImported() {
        return office.query(c -> c.preferences().authorizationImported());
    }

    @Override
    public boolean isRetroTicket(MissionId mission, TicketId ticket) {
        return office.query(c ->
                c.retros().find(mission).map(r -> ticket.equals(r.ticket())).orElse(false));
    }
}
