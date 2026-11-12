package dev.floor42.domain;

import java.util.List;

/**
 * The planner's advice on reinforcing the team of a mission (asked by the director from the board): whether more
 * developers would finish sooner at a cost worth paying, and whom to hire. It binds nobody: the director answers the
 * question it raises, and only then does Floor42 hire.
 */
public final class StaffingReview {

    /** One developer the planner proposes to hire: a name and a job title saying its speciality. */
    public record Hire(String name, String title) {}

    public static final int MAX_HIRES = 3;
    public static final String HIRE = OptionCode.HIRE.label();
    public static final String HIRE_ANYWAY = OptionCode.HIRE_ANYWAY.label();
    public static final String NOT_NOW = OptionCode.NOT_NOW.label();

    private final MissionId mission;
    private final TicketId ticket;
    private final AgentId planner;
    boolean advised;
    boolean worthIt;
    List<Hire> hires = List.of();
    Integer parallel;
    QuestionId question;

    StaffingReview(MissionId mission, TicketId ticket, AgentId planner) {
        this.mission = mission;
        this.ticket = ticket;
        this.planner = planner;
    }

    public MissionId mission() {
        return mission;
    }

    /** The review's own ticket, worked by the planner alone and done with the advice as its deliverable. */
    public TicketId ticket() {
        return ticket;
    }

    public AgentId planner() {
        return planner;
    }

    public boolean advised() {
        return advised;
    }

    /** The planner's verdict: reinforcing is worth its cost. */
    public boolean worthIt() {
        return worthIt;
    }

    /** Who to hire if the director agrees; empty when the planner sees no one to add. */
    public List<Hire> hires() {
        return hires;
    }

    /** The parallel sessions the planner advises with the hires; null to keep the setting. */
    public Integer parallel() {
        return parallel;
    }

    /** The director's yes or no; null when there was nothing to ask. */
    public QuestionId question() {
        return question;
    }
}
