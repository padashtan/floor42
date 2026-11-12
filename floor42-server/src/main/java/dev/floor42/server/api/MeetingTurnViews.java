package dev.floor42.server.api;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.Meeting;
import dev.floor42.domain.MeetingRound;
import dev.floor42.domain.MeetingTurn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Read models of the turns of speech at the director's meeting (ADR-0049), built inside {@code OfficeService.query}. */
public final class MeetingTurnViews {

    private MeetingTurnViews() {}

    /** Where a turn stands: waiting for its speaker, the speaker speaking, heard, or not heard (and why). */
    public enum TurnState {
        WAITING,
        SPEAKING,
        DONE,
        NOT_HEARD
    }

    /**
     * What a turn cost: the session's own estimate by Claude Code, the budget the meeting was charged when Claude Code
     * gave none, or nothing (no session ran). Never measured.
     */
    public enum CostBasis {
        ESTIMATED_BY_CLAUDE_CODE,
        BUDGET_CHARGED
    }

    /**
     * One speaker's turn of a round.
     *
     * @param answer null unless DONE
     * @param why null unless NOT_HEARD
     * @param costUsd null when nothing is charged; see {@code costBasis}
     * @param at when the turn was recorded; null while WAITING or SPEAKING
     */
    public record TurnView(
            String attendee,
            String name,
            String role,
            TurnState state,
            String answer,
            String why,
            Double costUsd,
            CostBasis costBasis,
            String at) {}

    /** @param question null when the agenda says what is asked */
    public record RoundView(int number, String question, double capUsd, String askedAt, List<TurnView> turns) {
        public RoundView {
            turns = turns == null ? null : Collections.unmodifiableList(new ArrayList<>(turns));
        }
    }

    static List<RoundView> of(Company c, Meeting m) {
        return m.rounds().stream().map(r -> round(c, m, r)).toList();
    }

    private static RoundView round(Company c, Meeting m, MeetingRound r) {
        List<TurnView> turns = r.speakers().stream()
                .map(a -> r.turnOf(a).map(t -> taken(c, t)).orElseGet(() -> pending(c, m, a)))
                .toList();
        return new RoundView(r.number(), r.question(), r.capUsd(), r.askedAt().toString(), turns);
    }

    private static TurnView taken(Company c, MeetingTurn t) {
        Double estimate = Optional.ofNullable(t.session())
                .flatMap(s -> c.sessions().find(s))
                .map(AgentSession::costUsdEstimated)
                .orElse(null);
        CostBasis basis = estimate != null
                ? CostBasis.ESTIMATED_BY_CLAUDE_CODE
                : t.budgetUsd() != null ? CostBasis.BUDGET_CHARGED : null;
        Double cost = estimate != null ? estimate : t.budgetUsd();
        TurnState state = t.heard() ? TurnState.DONE : TurnState.NOT_HEARD;
        return view(c, t.attendee(), state, t.answer(), t.why(), cost, basis, t.at().toString());
    }

    private static TurnView pending(Company c, Meeting m, AgentId a) {
        TurnState state = speaking(c, m, a) ? TurnState.SPEAKING : TurnState.WAITING;
        return view(c, a, state, null, null, null, null, null);
    }

    private static TurnView view(
            Company c, AgentId a, TurnState state, String answer, String why, Double cost, CostBasis basis, String at) {
        var agent = c.staff().find(a);
        return new TurnView(
                a.value(),
                agent.map(x -> x.displayName()).orElse(a.value()),
                agent.map(x -> x.role()).orElse(""),
                state,
                answer,
                why,
                cost,
                basis,
                at);
    }

    /** The agent's turn session runs now: a session open on the meeting's own ticket. */
    static boolean speaking(Company c, Meeting m, AgentId a) {
        return m.ticket() != null
                && c.sessions().of(m.mission()).stream()
                        .anyMatch(s ->
                                s.isOpen() && s.agent().equals(a) && s.ticket().equals(m.ticket()));
    }
}
