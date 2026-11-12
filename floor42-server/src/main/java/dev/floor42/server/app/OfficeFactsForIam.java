package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.OfficeFacts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Optional;

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
    public boolean isRetroTicket(MissionId mission, TicketId ticket) {
        return office.query(c ->
                c.retros().find(mission).map(r -> ticket.equals(r.ticket())).orElse(false));
    }
}
