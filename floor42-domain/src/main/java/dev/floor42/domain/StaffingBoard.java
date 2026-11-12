package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.StaffingEvent;
import dev.floor42.domain.event.StaffingEvent.StaffingAdvised;
import dev.floor42.domain.event.StaffingEvent.StaffingReviewRequested;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketStarted;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Reinforcing the team: the planner advises whether more developers are worth it, the director decides. */
final class StaffingBoard {

    private final CompanyView view;
    private final Map<MissionId, List<StaffingReview>> staffing = new LinkedHashMap<>();

    StaffingBoard(CompanyView view) {
        this.view = view;
    }

    Optional<StaffingReview> staffingReview(MissionId mission) {
        List<StaffingReview> all = staffing.getOrDefault(mission, List.of());
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    boolean isStaffingTicket(TicketId ticket) {
        return staffing.values().stream()
                .flatMap(List::stream)
                .anyMatch(r -> r.ticket().equals(ticket));
    }

    /** The review gets its own ticket, the director's (no approval), assigned to the planner and started at once. */
    List<EventEnvelope> requestStaffingReview(
            CommandContext ctx, MissionId mission, AgentId planner, TicketCreation tickets) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, mission);
        require(m.status == MissionStatus.IN_PROGRESS, Refusal.MISSION_NOT_ACTIVE, mission + " is " + m.status);
        // Floor42 names its planner (Galen); the domain only checks he is still in the company.
        require(!view.existingAgent(planner).dismissed, Refusal.AGENT_DISMISSED, planner + " has left the company");
        staffingReview(mission)
                .ifPresent(r -> require(
                        r.advised(),
                        Refusal.STAFFING_UNDER_WAY,
                        "The planner is already reviewing the team of " + mission));
        List<EventEnvelope> events = new ArrayList<>(tickets.create(
                ctx,
                mission,
                "Staffing: should the team be reinforced?",
                "Would more developers finish this mission sooner, at a cost worth paying? Asked by the director.",
                Priority.NORMAL,
                List.of(),
                List.of("Says whether to hire, whom, and at what cost, from the facts Floor42 measured")));
        TicketId ticket = ((TicketCreated) events.getFirst().event()).ticket();
        events.addAll(view.record(ctx, mission)
                .as(Actor.system())
                .emit(new TicketAssigned(ticket, planner))
                .as(Actor.agent(planner))
                .emit(new TicketStarted(ticket))
                .as(Actor.system())
                .emit(new StaffingReviewRequested(mission, ticket, planner))
                .events());
        return events;
    }

    /**
     * Records the planner's advice (or, from Floor42, why there is none) and, when it names developers to hire, asks the
     * director yes or no. The question blocks nothing: the mission goes on meanwhile.
     */
    List<EventEnvelope> adviseStaffing(
            CommandContext ctx,
            MissionId mission,
            String advice,
            boolean worthIt,
            List<StaffingReview.Hire> hires,
            Integer parallel,
            String figures) {
        StaffingReview r = staffingReview(mission)
                .orElseThrow(() -> new DomainException(Refusal.NO_STAFFING_REVIEW, "No staffing review on " + mission));
        view.missionFor(ctx, mission);
        require(!r.advised(), Refusal.STAFFING_ADVISED, "The staffing review of " + mission + " is already advised");
        require(
                ctx.actor().is(r.planner()) || ctx.actor().kind() == Actor.Kind.SYSTEM,
                Refusal.FORBIDDEN,
                "Only the planner advises, or Floor42 says why it could not");
        requireText(advice, "advice");
        List<StaffingReview.Hire> h = hires == null ? List.of() : List.copyOf(hires);
        require(h.size() <= StaffingReview.MAX_HIRES, Refusal.TOO_MANY_HIRES, "At most " + StaffingReview.MAX_HIRES);
        h.forEach(x -> requireText(x.name(), "hire name"));
        require(parallel == null || (parallel >= 1 && parallel <= 4), Refusal.INVALID_ARGUMENT, "parallel is 1 to 4");
        StaffingAdvised advised = new StaffingAdvised(mission, r.ticket(), advice, worthIt, h, parallel);
        Recorder rec = view.record(ctx, mission).emit(advised);
        if (!h.isEmpty() && view.mission(mission).orElseThrow().status == MissionStatus.IN_PROGRESS) {
            rec.as(Actor.system()).emit(hireQuestion(r, advised, figures));
        }
        return rec.events();
    }

    private QuestionAsked hireQuestion(StaffingReview r, StaffingAdvised advised, String figures) {
        boolean worthIt = advised.worthIt();
        Integer parallel = advised.parallel();
        String who = advised.hires().stream()
                .map(x -> x.name() + " (" + x.title() + ")")
                .collect(Collectors.joining(", "));
        List<String> options = worthIt
                ? List.of(StaffingReview.HIRE, StaffingReview.NOT_NOW)
                : List.of(StaffingReview.NOT_NOW, StaffingReview.HIRE_ANYWAY);
        return new QuestionAsked(
                view.nextQuestionId(),
                advised.mission(),
                QuestionKind.STAFFING,
                r.planner(),
                "Reinforce the team: hire " + who + "?",
                (worthIt ? "The planner advises it. " : "The planner advises against it. ")
                        + (parallel == null ? "" : "Parallel sessions would go to " + parallel + ". ")
                        + (figures == null ? "" : figures),
                options,
                worthIt ? StaffingReview.HIRE : StaffingReview.NOT_NOW,
                Criticality.MEDIUM,
                false,
                null,
                null,
                Phrase.of("staffing", "who", who),
                OptionCode.codesOf(options));
    }

    /** The only way this area's state changes. */
    void apply(StaffingEvent event, EventEnvelope envelope) {
        switch (event) {
            case StaffingReviewRequested e -> on(e);
            case StaffingAdvised e -> on(e);
        }
    }

    void on(StaffingReviewRequested e) {
        staffing.computeIfAbsent(e.mission(), k -> new ArrayList<>())
                .add(new StaffingReview(e.mission(), e.ticket(), e.planner()));
    }

    /** The review's part; its ticket is done, which is the ticket board's part. */
    void on(StaffingAdvised e) {
        StaffingReview r = staffing.get(e.mission()).getLast();
        r.advised = true;
        r.worthIt = e.worthIt();
        r.hires = List.copyOf(e.hires());
        r.parallel = e.parallel();
    }

    /** The question asked about a staffing review is the review's. */
    void asked(QuestionAsked e) {
        if (e.kind() == QuestionKind.STAFFING) staffing.get(e.mission()).getLast().question = e.question();
    }
}
