package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.blankToNull;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireSystem;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingTurnTaken;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked;
import java.util.List;

/**
 * The rules of the turns of speech at the director's meeting (ADR-0049): the director asks some attendees for their
 * view, in an order, within a cost cap for the whole meeting; Floor42's runtime records each turn, in that order. The
 * state stays the meetings area's: this part only checks and emits.
 */
final class MeetingRounds {

    /** The most a meeting may be allowed to spend, in USD as Claude Code estimates it. */
    static final double MAX_CAP_USD = 20.0;

    static final int MAX_ANSWER = 4_000;

    private final CompanyView view;

    MeetingRounds(CompanyView view) {
        this.view = view;
    }

    List<EventEnvelope> ask(CommandContext ctx, Meeting mt, List<AgentId> speakers, double capUsd, String question) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, mt.mission());
        require(m.origin() == Origin.REAL, Refusal.SIMULATED_MISSION, m.id() + " is simulated: nobody speaks");
        require(
                m.status() == MissionStatus.IN_PROGRESS,
                Refusal.MISSION_NOT_ACTIVE,
                m.id() + " is " + m.status() + ": attendees speak about a mission in progress");
        require(
                mt.roundUnderWay().isEmpty(),
                Refusal.ROUND_UNDER_WAY,
                "A round is under way at " + mt.id() + ": wait for its last speaker");
        List<AgentId> who = speakers(mt, speakers);
        requireCap(mt, capUsd);
        String asked = blankToNull(question);
        require(
                asked == null || asked.length() <= Meetings.MAX_AGENDA,
                Refusal.INVALID_ARGUMENT,
                "A question is at most " + Meetings.MAX_AGENDA + " characters");
        // Named after the meeting, outside the T-n sequence: a daemon that cannot read this row never reuses its id.
        TicketId ticket = mt.ticket() != null ? mt.ticket() : new TicketId(mt.id() + ".turns");
        return view.record(ctx, m.id())
                .emit(new MeetingViewsAsked(
                        mt.id(), ticket, mt.rounds().size() + 1, who, capUsd, asked == null ? null : asked.strip()))
                .events();
    }

    private static List<AgentId> speakers(Meeting mt, List<AgentId> speakers) {
        require(speakers != null && !speakers.isEmpty(), "MISSING_SPEAKERS", "Name at least one speaker");
        List<AgentId> who = speakers.stream().distinct().toList();
        require(who.size() == speakers.size(), Refusal.INVALID_ARGUMENT, "Each speaker speaks once a round");
        for (AgentId a : who) require(mt.attends(a), Refusal.NOT_AN_ATTENDEE, a + " does not attend " + mt.id());
        return who;
    }

    /** The cap is the meeting's, whatever round sets it: above what it spent already, at most {@link #MAX_CAP_USD}. */
    private void requireCap(Meeting mt, double capUsd) {
        require(
                capUsd > 0 && capUsd <= MAX_CAP_USD,
                Refusal.INVALID_ARGUMENT,
                "A meeting's cost cap is above 0 and at most " + MAX_CAP_USD + " USD");
        require(
                Usd.micros(capUsd) - spentMicroUsd(mt) >= Usd.micros(MeetingsFacade.MIN_TURN_USD),
                Refusal.INVALID_ARGUMENT,
                mt.id() + " was charged " + Usd.usd(spentMicroUsd(mt)) + " USD already: a cap leaves room for a turn"
                        + " only " + MeetingsFacade.MIN_TURN_USD + " USD above it, or more");
    }

    private Double estimateOf(MeetingTurn t) {
        return t.session() == null
                ? null
                : view.session(t.session()).map(AgentSession::costUsdEstimated).orElse(null);
    }

    /**
     * What the meeting's turns cost so far: each session as Claude Code estimated it, or, when it gave no estimate (a
     * session that failed or was stopped), the most it was allowed. Never less than what may have been spent.
     */
    long spentMicroUsd(Meeting mt) {
        return mt.rounds().stream()
                .flatMap(r -> r.turns().stream())
                .mapToLong(this::chargeOf)
                .sum();
    }

    private long chargeOf(MeetingTurn t) {
        Double estimate = estimateOf(t);
        if (estimate != null) return Usd.micros(estimate);
        return t.budgetUsd() == null ? 0 : Usd.micros(t.budgetUsd());
    }

    /**
     * Floor42's runtime records a turn, the round's next speaker's only: its answer, or why it was not heard. A paid
     * answer is never refused for its length: one longer than {@link #MAX_ANSWER} is kept cut, and says so.
     */
    List<EventEnvelope> turn(CommandContext ctx, Meeting mt, SpokenTurn turn) {
        requireSystem(ctx, "Only Floor42's runtime records a turn of speech");
        view.missionFor(ctx, mt.mission());
        int round = turn.round();
        require(round >= 1 && round <= mt.rounds().size(), Refusal.UNKNOWN_ROUND, mt.id() + " has no round " + round);
        require(
                mt.rounds().get(round - 1).next().map(turn.attendee()::equals).orElse(false),
                Refusal.NOT_THEIR_TURN,
                turn.attendee() + " is not the next speaker of round " + round + " at " + mt.id());
        String said = blankToNull(turn.answer());
        String reason = blankToNull(turn.why());
        require((said == null) != (reason == null), Refusal.INVALID_ARGUMENT, "A turn is an answer or a reason");
        require(
                turn.session() == null || ownSession(mt, turn),
                Refusal.UNKNOWN_SESSION,
                turn.session() + " is no turn of " + turn.attendee() + " at " + mt.id());
        return view.record(ctx, mt.mission())
                .emit(new MeetingTurnTaken(
                        mt.id(),
                        round,
                        turn.attendee(),
                        turn.session(),
                        said == null ? null : cut(said.strip()),
                        reason == null ? null : reason.strip(),
                        turn.budgetUsd()))
                .events();
    }

    /** The speaker's session on the meeting's ticket, started since its round was asked, named by no turn yet. */
    private boolean ownSession(Meeting mt, SpokenTurn turn) {
        return view.session(turn.session())
                .map(s -> s.ticket().equals(mt.ticket())
                        && s.agent().equals(turn.attendee())
                        && !s.startedAt()
                                .isBefore(mt.rounds().get(turn.round() - 1).askedAt())
                        && !named(mt, s.id()))
                .orElse(false);
    }

    /** A turn already names this session: one session is one turn, charged once. */
    private static boolean named(Meeting mt, String session) {
        return mt.rounds().stream().flatMap(r -> r.turns().stream()).anyMatch(t -> session.equals(t.session()));
    }

    static String cut(String answer) {
        if (answer.length() <= MAX_ANSWER) return answer;
        return answer.substring(0, MAX_ANSWER - CUT_NOTE.length()) + CUT_NOTE;
    }

    static final String CUT_NOTE = " [cut by Floor42: the answer was longer]";
}
