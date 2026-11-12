package dev.floor42.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every refusal the domain can make, by its stable code (the API's {@code code}), and what kind of refusal it is. The
 * HTTP layer maps the kind to a status: the request is wrong (400), what it names does not exist (404), the state of
 * things refuses it (409). A missing field is refused as {@code MISSING_<FIELD>}, an invalid request.
 */
public enum Refusal {
    ACTIONS_RUNNING(Kind.CONFLICT),
    ASK_BACK_CAP(Kind.CONFLICT),
    ASK_BACK_NOT_ALLOWED(Kind.CONFLICT),
    ASK_BACK_TEXT(Kind.INVALID),
    AGENT_AT_WORK(Kind.CONFLICT),
    AGENT_BUSY(Kind.CONFLICT),
    AGENT_DISMISSED(Kind.CONFLICT),
    AGENT_EXISTS(Kind.CONFLICT),
    AGENT_IN_COMMITTEE(Kind.CONFLICT),
    AGENT_IN_MEETING(Kind.CONFLICT),
    AGENT_IN_RETRO(Kind.CONFLICT),
    AGENT_IN_STAFFING(Kind.CONFLICT),
    AGENT_PRESENT(Kind.CONFLICT),
    AGENT_SUSPENDED(Kind.CONFLICT),
    ALREADY_CONSULTED(Kind.CONFLICT),
    ALREADY_DISMISSED(Kind.CONFLICT),
    ALREADY_SUSPENDED(Kind.CONFLICT),
    BLOCKED_BY_QUESTION(Kind.CONFLICT),
    BUDGET_AVAILABLE(Kind.CONFLICT),
    CAP_NEEDED(Kind.CONFLICT),
    DECIDED_TICKET(Kind.CONFLICT),
    DEPENDENCY_OPEN(Kind.CONFLICT),
    DIRECTOR_ONLY(Kind.CONFLICT),
    DUPLICATE_ACTION(Kind.CONFLICT),
    DUPLICATE_AGENT(Kind.CONFLICT),
    DUPLICATE_COMMITTEE(Kind.CONFLICT),
    DUPLICATE_RETRO(Kind.CONFLICT),
    DUPLICATE_SESSION(Kind.CONFLICT),
    DUPLICATE_WORKSPACE(Kind.CONFLICT),
    EMPTY_PATCH(Kind.CONFLICT),
    EMPTY_PLAN(Kind.CONFLICT),
    FORBIDDEN(Kind.CONFLICT),
    GRAPH_NEEDS_PLAN(Kind.CONFLICT),
    HEAD_IS_BASE(Kind.CONFLICT),
    INVALID_ANSWER(Kind.INVALID),
    INVALID_ARGUMENT(Kind.INVALID),
    INVALID_COMMIT(Kind.INVALID),
    INVALID_TRANSITION(Kind.CONFLICT),
    MEETING_CLOSED(Kind.CONFLICT),
    MEETING_OPEN(Kind.CONFLICT),
    MEETING_TICKET(Kind.CONFLICT),
    MISSION_ARCHIVED(Kind.CONFLICT),
    MISSION_CLOSED(Kind.CONFLICT),
    MISSION_NOT_ACTIVE(Kind.CONFLICT),
    MISSION_NOT_FINISHED(Kind.CONFLICT),
    MISSION_STILL_OPEN(Kind.CONFLICT),
    NOTE_ALREADY_TICKET(Kind.CONFLICT),
    NOTE_NOT_IMPLEMENTED(Kind.CONFLICT),
    NOT_AN_ATTENDEE(Kind.CONFLICT),
    NOT_AT_ACCEPTANCE(Kind.CONFLICT),
    NOT_ASSIGNEE(Kind.CONFLICT),
    NOT_A_PARTICIPANT(Kind.CONFLICT),
    NOT_THEIR_TURN(Kind.CONFLICT),
    NOT_CONTINUABLE(Kind.CONFLICT),
    NOT_EXPECTED(Kind.CONFLICT),
    NOT_LATEST_SESSION(Kind.CONFLICT),
    NOT_ON_TICKET(Kind.CONFLICT),
    NOTHING_TO_REPLAN(Kind.CONFLICT),
    NOT_PROPOSED(Kind.CONFLICT),
    NOT_REMEMBERED(Kind.CONFLICT),
    NOT_SUSPENDED(Kind.CONFLICT),
    NO_CHECKS(Kind.CONFLICT),
    NO_COMMITTEE(Kind.CONFLICT),
    NO_CONSULTATION(Kind.CONFLICT),
    NO_DESTINATION(Kind.CONFLICT),
    NO_DIRECTOR_SEAT(Kind.CONFLICT),
    NO_PRODUCTION_IN_WORKFLOW(Kind.CONFLICT),
    NO_REPOSITORY(Kind.CONFLICT),
    NO_RETRO(Kind.CONFLICT),
    NO_STAFFING_REVIEW(Kind.CONFLICT),
    NO_TICKET_UNDER_WAY(Kind.CONFLICT),
    NO_VALIDATION(Kind.CONFLICT),
    ORIGIN_MISMATCH(Kind.CONFLICT),
    OUTCOME_ALREADY_RECORDED(Kind.CONFLICT),
    PLAN_LOCKED(Kind.CONFLICT),
    PLAN_TOO_LONG(Kind.INVALID),
    PROPOSAL_CLOSED(Kind.CONFLICT),
    PULL_REQUEST_RECORDED(Kind.CONFLICT),
    QUESTION_CLOSED(Kind.CONFLICT),
    RETRO_CONCLUDED(Kind.CONFLICT),
    RETRO_NOT_READY(Kind.CONFLICT),
    RETRO_UNDER_WAY(Kind.CONFLICT),
    REVIEWER_NOT_AGENT(Kind.CONFLICT),
    ROUND_UNDER_WAY(Kind.CONFLICT),
    SELF_CONFIRMATION(Kind.CONFLICT),
    SELF_REVIEW(Kind.CONFLICT),
    SESSION_CLOSED(Kind.CONFLICT),
    SESSION_OPEN(Kind.CONFLICT),
    SESSION_RUNNING(Kind.CONFLICT),
    SIMULATED_MISSION(Kind.CONFLICT),
    SIMULATED_WORKSPACE(Kind.CONFLICT),
    STAFFING_ADVISED(Kind.CONFLICT),
    STAFFING_UNDER_WAY(Kind.CONFLICT),
    STEP_MOVED_ON(Kind.CONFLICT),
    TERMINAL_OPEN(Kind.CONFLICT),
    TOO_MANY_HIRES(Kind.CONFLICT),
    UNAUTHORIZED_ACTION(Kind.CONFLICT),
    UNKNOWN_ACTION(Kind.NOT_FOUND),
    UNKNOWN_AGENT(Kind.NOT_FOUND),
    UNKNOWN_DEPENDENCY(Kind.INVALID),
    UNKNOWN_LESSON(Kind.NOT_FOUND),
    UNKNOWN_MEETING(Kind.NOT_FOUND),
    UNKNOWN_MISSION(Kind.NOT_FOUND),
    UNKNOWN_NOTE(Kind.NOT_FOUND),
    UNKNOWN_PROJECT(Kind.NOT_FOUND),
    UNKNOWN_PROPOSAL(Kind.NOT_FOUND),
    UNKNOWN_PULL_REQUEST(Kind.NOT_FOUND),
    UNKNOWN_QUESTION(Kind.NOT_FOUND),
    UNKNOWN_ROUND(Kind.NOT_FOUND),
    UNKNOWN_RULE(Kind.NOT_FOUND),
    UNKNOWN_SESSION(Kind.NOT_FOUND),
    UNKNOWN_TERMINAL(Kind.NOT_FOUND),
    UNKNOWN_TICKET(Kind.NOT_FOUND),
    UNKNOWN_WORKFLOW(Kind.NOT_FOUND),
    UNKNOWN_WORKSPACE(Kind.NOT_FOUND),
    WORKFLOW_EXISTS(Kind.CONFLICT),
    WORKFLOW_PRODUCES_NOTHING(Kind.CONFLICT),
    WORKFLOW_SCHEMA_NOT_WRITABLE(Kind.CONFLICT),
    WORKFLOW_UNREADABLE(Kind.CONFLICT),
    WORK_STARTED(Kind.CONFLICT);

    /** What kind of refusal: what the HTTP layer answers with. */
    public enum Kind {
        /** The request itself is wrong, whatever the state. */
        INVALID,
        /** What the request names does not exist. */
        NOT_FOUND,
        /** The state of things refuses it. */
        CONFLICT
    }

    static final String MISSING = "MISSING_";

    private final Kind kind;

    Refusal(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** The code as the API and the journal's readers know it. */
    public String code() {
        return name();
    }

    /** The kind of a domain code, a missing field's included; empty for a code the domain never gives. */
    public static Optional<Kind> kindOf(String code) {
        if (code != null && code.startsWith(MISSING)) return Optional.of(Kind.INVALID);
        return Arrays.stream(values())
                .filter(r -> r.name().equals(code))
                .findFirst()
                .map(Refusal::kind);
    }
}
