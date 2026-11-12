package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.Mission;
import dev.floor42.domain.ProposedTicket;
import dev.floor42.domain.Retro;
import dev.floor42.domain.StaffingReview;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * What a session is told, built from the journal at launch time, never from a chat history. An agent may lose its
 * context; the company does not: everything that matters (objective, criteria, permissions, the director's decisions,
 * previous reviews) is restated here from persisted facts.
 *
 * <p>The entry points of every brief and of every parser of an answer. Each role has its own class ({@link
 * ProducerBrief}, {@link PlannerBrief}, {@link ReviewerBrief}, {@link ConsultantBrief}, {@link CommitteeBriefs},
 * {@link RetroBriefs}, {@link StaffingBrief}) sharing {@link BriefSections} and {@link BriefPermissions}; the fixed prose
 * is in {@code src/main/resources/briefs}; the answers are read by {@link BriefParsers}.
 */
public final class Briefs {

    /** The reviewer ends with this marker; anything else is treated as unverifiable and escalated. */
    public static final String VERDICT_MARKER = ReviewerBrief.VERDICT_MARKER;

    /** Most tickets a planner may propose: more is a plan the director cannot weigh ticket by ticket. */
    public static final int MAX_PLANNED_TICKETS = PlannerBrief.MAX_PLANNED_TICKETS;

    private Briefs() {}

    // ---- what Floor42 reads back --------------------------------------------------------------------------------

    /**
     * Closing lines of a committee contribution, read back by Floor42.
     *
     * @param questions what a committee member could not settle without the director (its QUESTIONS line)
     */
    public record Summary(
            String proposal, String position, String criticalObjection, String recommendation, List<String> questions) {
        public Summary {
            questions = questions == null ? null : Collections.unmodifiableList(new ArrayList<>(questions));
        }
    }

    /** A reviewer's request for help, read back by Floor42 (ADR-0018). */
    public record Help(ConsultationKind kind, String role, String question) {}

    /** The chair's conclusions and lessons; a lesson's role prefix is read when it names a role, else it is for all. */
    public record RetroConclusion(List<String> conclusions, List<Lesson.Proposal> lessons) {
        public RetroConclusion {
            conclusions = conclusions == null ? null : Collections.unmodifiableList(new ArrayList<>(conclusions));
            lessons = lessons == null ? null : Collections.unmodifiableList(new ArrayList<>(lessons));
        }
    }

    /** What the planner advised, read from its marker lines; the whole answer is kept as written. */
    public record StaffingAdvice(boolean worthIt, List<StaffingReview.Hire> hires, Integer parallel) {
        public StaffingAdvice {
            hires = hires == null ? null : Collections.unmodifiableList(new ArrayList<>(hires));
        }
    }

    /** The last occurrence of each closing line wins; "none" means no critical objection. */
    public static Summary summary(String text) {
        return BriefParsers.summary(text);
    }

    /** The business analyst's questions for the director (see {@link BriefParsers#clarifications}). */
    public static List<Committee.Clarification> clarifications(String text) {
        return BriefParsers.clarifications(text);
    }

    /** The tickets of a planner's report, in order (see {@link BriefParsers#plan}). */
    public static List<ProposedTicket> plan(String text) {
        return BriefParsers.plan(text);
    }

    /** The nodes of a planner's report in graph mode (see {@link BriefParsers#plan(String, boolean)}). */
    public static List<ProposedTicket> graph(String text) {
        return BriefParsers.plan(text, true);
    }

    /** The new nodes of a graph extension (ADR-0062): as {@link #graph}, with the T-ids of the nodes they build on. */
    public static List<ProposedTicket> extension(String text) {
        return BriefParsers.plan(text, true, true);
    }

    /** The reviewer's last CONSULT or RESEARCH line, if any; a consultation names a role before its question. */
    public static Optional<Help> help(String text) {
        return BriefParsers.help(text);
    }

    /** The lines of a retrospective answer starting with this marker, in order, without the marker. */
    public static List<String> retroLines(String text, String marker) {
        return BriefParsers.retroLines(text, marker);
    }

    public static RetroConclusion retroConclusion(String text) {
        return BriefParsers.retroConclusion(text);
    }

    public static StaffingAdvice staffingAdvice(String text) {
        return BriefParsers.staffingAdvice(text);
    }

    // ---- briefs -------------------------------------------------------------------------------------------------

    public static String producer(Company c, Mission m, Ticket t, Agent agent) {
        return ProducerBrief.of(c, m, t, agent);
    }

    /** @param t the planning ticket; the brief does not depend on it */
    public static String planner(Company c, Mission m, Ticket t, Agent agent) {
        return GraphExtensions.is(t) ? GraphExtensions.brief(c, m, t, agent) : PlannerBrief.of(c, m, agent);
    }

    public static String reviewer(Company c, Mission m, Ticket t, Agent agent) {
        return ReviewerBrief.of(c, m, t, agent);
    }

    /** A peer asked for advice by a reviewer: reads the producer's worktree, changes nothing, advises. */
    public static String consultant(Company c, Mission m, Ticket t, Agent agent, Ticket.Consultation h) {
        return ConsultantBrief.consultant(c, m, t, agent, h);
    }

    /** A web research for a reviewer, by a session that has no access to the repository: it only gets the question. */
    public static String researcher(Agent agent, Ticket.Consultation h) {
        return ConsultantBrief.researcher(agent, h);
    }

    /** A committee member's brief (spec sections 10.2 and 10.3). */
    public static String committeeMember(Company c, Mission m, Committee committee, Agent agent) {
        return CommitteeBriefs.member(c, m, committee, agent);
    }

    /** The chair's brief: the decision record (spec section 10.4), with disagreements kept visible. */
    public static String committeeChair(Company c, Mission m, Committee committee, Agent agent) {
        return CommitteeBriefs.chair(c, m, committee, agent);
    }

    /** The business analyst, between the committee's record and the director's ruling. */
    public static String analyst(Company c, Mission m, Committee committee, Agent agent) {
        return CommitteeBriefs.analyst(c, m, committee, agent);
    }

    /** The mission's facts from the journal, for every participant of its retrospective (ADR-0020). */
    public static String retroFacts(Company c, Mission m) {
        return RetroBriefs.facts(c, m);
    }

    /** A participant's turn: what went well and what did not, from the facts, in a few lines. */
    public static String retroParticipant(Company c, Mission m, Retro r, Agent agent) {
        return RetroBriefs.participant(m, r, agent);
    }

    /** The chair's conclusion: what the team keeps, what it changes, and at most a few lessons for the director. */
    public static String retroChair(Company c, Mission m, Retro r, Agent agent) {
        return RetroBriefs.chair(c, m, r, agent);
    }

    /** The planner's staffing review: whether more developers would finish sooner, at a cost worth paying. */
    public static String staffing(Mission m, Agent agent, String facts) {
        return StaffingBrief.of(m, agent, facts);
    }

    // ---- what every brief shares --------------------------------------------------------------------------------

    /** Tools worth offering at all: those the mission does not forbid for everyone. */
    public static List<String> offeredTools(Mission m) {
        return BriefPermissions.offeredTools(m);
    }

    /** The tools a session on this ticket is offered: a ticket's own rule may open one the mission keeps closed. */
    public static List<String> offeredTools(Mission m, TicketId ticket) {
        return BriefPermissions.offeredTools(m, ticket);
    }

    /**
     * Test seam (package-private: {@code BriefsCharacterisationTest}, and {@code LiveSeams} for the API tests): the
     * tools Floor42 last measured present in the sandbox (ADR-0033); nothing when no measure holds. Briefs write it
     * through {@link BriefPermissions}.
     */
    static String measuredTools(Company c) {
        return BriefPermissions.measuredTools(c);
    }

    /** The brief in the language the director chose for the agents; the marker lines stay in English. */
    public static String inLanguage(Company c, String brief) {
        return BriefPermissions.inLanguage(c, brief);
    }
}
