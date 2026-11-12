package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;

/**
 * The company: every mission, ticket, action and question, and the rules that govern them.
 *
 * <p>State changes only by applying events, so {@link #replay} rebuilds exactly the same company from the journal.
 * Commands check every rule first, then record their events; a refused command records nothing. Each area of the
 * company (staff, missions, tickets, the desk...) keeps its own state and applies its own events, in
 * {@link CompanyCore}; the director's vocabulary is reached through one facade per area, from this root:
 * {@code company.missions().create(...)}, {@code company.tickets().submitForReview(...)}.
 *
 * <p>Not thread-safe: the application layer serializes commands.
 */
public final class Company {

    private final CompanyCore core = new CompanyCore();
    private final TicketsFacade tickets = new TicketsFacade(core.ticketBoard, core.ticketReviews, core.ticketPlanning);
    private final WorkshopFacade workshop = new WorkshopFacade(core.workbench);
    private final MissionsFacade missions = new MissionsFacade(
            core.missionBook, new Objections(core.view, core.ticketBoard.objectionRounds(), tickets, workshop));
    private final ActionsFacade actions = new ActionsFacade(core.actionLog);
    private final StaffFacade staff = new StaffFacade(core.roster, core.staffingBoard, tickets);
    private final DeskFacade desk = new DeskFacade(core.desk, core.answers);
    private final SessionsFacade sessions = new SessionsFacade(core.sessionLog);
    private final VerificationFacade verification = new VerificationFacade(core.verification);
    private final DeliveriesFacade deliveries = new DeliveriesFacade(core.deliveries);
    private final CommitteesFacade committees = new CommitteesFacade(core.committeeHall, core.committeeSeats);
    private final RetrosFacade retros = new RetrosFacade(core.retrospectives);
    private final PreferencesFacade preferences = new PreferencesFacade(core.preferences);
    private final NotebookFacade notebook = new NotebookFacade(core.notebook, tickets);
    private final TerminalsFacade terminals = new TerminalsFacade(core.terminals);
    private final ProjectsFacade projects = new ProjectsFacade(core.recentProjects);
    private final WorkflowsFacade workflows = new WorkflowsFacade(core.workflowBook);
    private final QueriesFacade queries = new QueriesFacade(core.queries, core);
    private final MeetingsFacade meetings = new MeetingsFacade(core.meetings, workshop);

    public static Company replay(Iterable<EventEnvelope> history) {
        Company company = new Company();
        history.forEach(company::apply);
        return company;
    }

    /** The only way the company changes: one event, from the journal or just recorded by a command. */
    public void apply(EventEnvelope envelope) {
        core.apply(envelope);
    }

    /** Number of events applied so far. */
    public long version() {
        return core.version();
    }

    /** The missions: created, planned, approved, run, ended, with their permission table. */
    public MissionsFacade missions() {
        return missions;
    }

    /** The tickets of the missions: created, approved, worked, reviewed. */
    public TicketsFacade tickets() {
        return tickets;
    }

    /** The agents' tool calls and the incidents they raised. */
    public ActionsFacade actions() {
        return actions;
    }

    /** The staff: hired, reconfigured, starred, suspended, dismissed; and the staffing reviews. */
    public StaffFacade staff() {
        return staff;
    }

    /** The director's desk: questions, answers and remembered approvals. */
    public DeskFacade desk() {
        return desk;
    }

    /** The agents' sessions and what they say in them. */
    public SessionsFacade sessions() {
        return sessions;
    }

    /** The workshop: the agents' workspaces and what the director does in them. */
    public WorkshopFacade workshop() {
        return workshop;
    }

    /** The mission's checks, its budget and the plan's usage windows. */
    public VerificationFacade verification() {
        return verification;
    }

    /** Pull requests, plugins and the skills and agents a mission receives. */
    public DeliveriesFacade deliveries() {
        return deliveries;
    }

    /** The committees and the seats the director defines. */
    public CommitteesFacade committees() {
        return committees;
    }

    /** The retrospectives of ended missions and their lessons. */
    public RetrosFacade retros() {
        return retros;
    }

    /** The director's settings, default permission table and the sandbox measure. */
    public PreferencesFacade preferences() {
        return preferences;
    }

    /** The director's improvement checklist. */
    public NotebookFacade notebook() {
        return notebook;
    }

    /** The director's terminals and script tokens. */
    public TerminalsFacade terminals() {
        return terminals;
    }

    /** The director's list of recent projects. */
    public ProjectsFacade projects() {
        return projects;
    }

    /** The director's meetings at the table with the agents of a mission (ADR-0047). */
    public MeetingsFacade meetings() {
        return meetings;
    }

    /** Workflows: what each mission pinned, the company's list, the planner's proposals (ADR-0050). */
    public WorkflowsFacade workflows() {
        return workflows;
    }

    /** What the company answers from several areas at once. */
    public QueriesFacade queries() {
        return queries;
    }
}
