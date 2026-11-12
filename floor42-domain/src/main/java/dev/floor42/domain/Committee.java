package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A committee (spec section 10): members analyse a question independently, then confront their analyses, then the
 * chair writes the record the director arbitrates. Disagreement is recorded, never staged: what is shown comes from
 * persisted contributions. An architecture committee debates once and its chair is a member; an idea committee has a
 * neutral moderator who does not take part, and debates again until its members agree or the rounds run out.
 */
public final class Committee {

    /**
     * How a committee runs.
     *
     * @param panel {@code architecture} or {@code idea}
     * @param moderated the chair only writes the record: it neither analyses nor debates
     * @param maxDebateRounds debate rounds before the record when the members do not agree
     * @param directorSeat the member the director may speak for in person, or null
     */
    public record Format(String panel, boolean moderated, int maxDebateRounds, AgentId directorSeat) {

        public static final Format ARCHITECTURE = new Format("architecture", false, 1, null);

        public Format {
            if (maxDebateRounds < 1) throw new IllegalArgumentException("At least one debate round");
        }
    }

    public enum Stage {
        /** Round 1: each member analyses alone, without seeing the others. */
        ANALYSIS,
        /** Later rounds: members read each other, object, answer, revise. */
        DEBATE,
        /** The chair writes the decision record. */
        SYNTHESIS,
        /** The business analyst gathers the members' questions for the director. */
        CLARIFICATION,
        /** The director answers the business analyst's questions; the committee then debates once more. */
        AWAITING_ANSWERS,
        AWAITING_DIRECTOR,
        ADOPTED,
        REJECTED
    }

    public enum Kind {
        ANALYSIS,
        REBUTTAL,
        SYNTHESIS
    }

    /**
     * One persisted contribution (spec section 10.3). The three summary fields are read from the contribution's own
     * closing lines; null means the member did not state it.
     *
     * @param byDirector written by the director in person, in the seat of their double
     * @param questions what the member could not settle without the director, from its QUESTIONS line
     */
    public record Contribution(
            AgentId member,
            int round,
            Kind kind,
            String text,
            String proposal,
            String position,
            String criticalObjection,
            Instant at,
            boolean byDirector,
            List<String> questions) {
        public Contribution {
            questions = questions == null ? null : Collections.unmodifiableList(new ArrayList<>(questions));
        }
    }

    /** A question the business analyst asks the director: options when the answer is a choice, none for a free one. */
    public record Clarification(String question, String context, List<String> options, String recommendation) {
        public Clarification {
            options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
        }
    }

    /** How many times a committee goes through the business analyst at most: then the record goes to the director. */
    public static final int MAX_CLARIFICATIONS = 2;

    private final MissionId mission;
    private final int number;
    private final TicketId ticket;
    private final String topic;
    private final Map<AgentId, String> members = new LinkedHashMap<>();
    private final Format format;
    final List<Contribution> contributions = new ArrayList<>();
    final List<String> constraints = new ArrayList<>();
    Stage stage = Stage.ANALYSIS;
    int round = 1;
    boolean implement;
    /** The business analyst of the current clarification. */
    AgentId analyst;
    /** Questions raised in rounds up to this one were already taken to the director. */
    int clarifiedUpTo;

    int clarifications;
    /** The business analyst's latest note: what it gathered, settled itself, asks. */
    String clarificationNote;

    /** The questions of the current clarification. */
    final List<QuestionId> clarificationQuestions = new ArrayList<>();

    Committee(
            CommitteeRef ref, TicketId ticket, String topic, List<AgentId> members, List<String> roles, Format format) {
        this.mission = ref.mission();
        this.number = ref.number();
        this.ticket = ticket;
        this.topic = topic;
        this.format = format;
        for (int i = 0; i < members.size(); i++) this.members.put(members.get(i), roles.get(i));
    }

    public Format format() {
        return format;
    }

    /** The members who analyse and debate, in seating order: everyone but a moderator. */
    public List<AgentId> debaters() {
        return members.keySet().stream()
                .filter(m -> !(format.moderated() && m.equals(chair())))
                .toList();
    }

    public MissionId mission() {
        return mission;
    }

    /** Its number in the mission, from 1 in the order committees were convened. */
    public int number() {
        return number;
    }

    /** How every query and command names this committee. */
    public CommitteeRef ref() {
        return new CommitteeRef(mission, number);
    }

    /** The decision ticket; the chair is its assignee. */
    public TicketId ticket() {
        return ticket;
    }

    public String topic() {
        return topic;
    }

    /** Members and their roles, chair first. */
    public Map<AgentId, String> members() {
        return Map.copyOf(members);
    }

    public List<AgentId> memberIds() {
        return List.copyOf(members.keySet());
    }

    public AgentId chair() {
        return members.keySet().iterator().next();
    }

    public String roleOf(AgentId member) {
        return members.get(member);
    }

    public Stage stage() {
        return stage;
    }

    public int round() {
        return round;
    }

    public List<Contribution> contributions() {
        return List.copyOf(contributions);
    }

    /** Constraints the director added when sending the committee back. */
    public List<String> constraints() {
        return List.copyOf(constraints);
    }

    /** Whether the director asked to implement the adopted decision. */
    public boolean implement() {
        return implement;
    }

    public boolean isMember(AgentId agent) {
        return members.containsKey(agent);
    }

    /** This is the committee's decision ticket and the director already ruled on it: nobody works on it any more. */
    boolean ruledOn(TicketId t) {
        return ticket.equals(t) && ruled();
    }

    /** The director ruled: adopted or rejected. A mission may convene its next committee only then. */
    public boolean ruled() {
        return stage == Stage.ADOPTED || stage == Stage.REJECTED;
    }

    /** In session: analysing, debating, writing the record, or going through the business analyst. */
    public boolean deliberating() {
        return stage == Stage.ANALYSIS
                || stage == Stage.DEBATE
                || stage == Stage.SYNTHESIS
                || stage == Stage.CLARIFICATION;
    }

    /** The business analyst of the current clarification; null when there is none. */
    public AgentId analyst() {
        return stage == Stage.CLARIFICATION || stage == Stage.AWAITING_ANSWERS ? analyst : null;
    }

    public String clarificationNote() {
        return clarificationNote;
    }

    public int clarifications() {
        return clarifications;
    }

    /** Contributions whose questions were not taken to the director yet. */
    public List<Contribution> unclarifiedQuestions() {
        return contributions.stream()
                .filter(c -> c.round() > clarifiedUpTo
                        && c.questions() != null
                        && !c.questions().isEmpty())
                .toList();
    }

    /** The members who still owe a contribution in the current round. */
    public List<AgentId> pending() {
        return switch (stage) {
            case ANALYSIS, DEBATE ->
                debaters().stream()
                        .filter(m ->
                                contributions.stream().noneMatch(c -> c.member().equals(m) && c.round() == round))
                        .toList();
            case SYNTHESIS ->
                contributions.stream().anyMatch(c -> c.kind() == Kind.SYNTHESIS && c.round() == round)
                        ? List.of()
                        : List.of(chair());
            case CLARIFICATION -> analyst == null ? List.of() : List.of(analyst);
            default -> List.of();
        };
    }

    /** Each member's latest analysis or rebuttal: where they stand now. */
    public List<Contribution> latestPositions() {
        List<Contribution> latest = new ArrayList<>();
        for (AgentId m : debaters()) {
            contributions.stream()
                    .filter(c -> c.member().equals(m) && c.kind() != Kind.SYNTHESIS)
                    .reduce((first, last) -> last)
                    .ifPresent(latest::add);
        }
        return latest;
    }

    /** Critical objections still standing in the members' latest positions: no majority can remove them. */
    public List<Contribution> standingCriticalObjections() {
        return latestPositions().stream()
                .filter(c -> c.criticalObjection() != null)
                .toList();
    }

    /**
     * Whose proposal every debater stands behind, read from their closing lines, when they all agree and no critical
     * objection stands: empty otherwise. {@code ADOPTED <member>} stands behind that member's proposal; {@code NEW},
     * {@code MAINTAINED} or {@code REVISED} behind one's own. A missing line is not agreement.
     */
    public java.util.Optional<AgentId> agreement() {
        List<Contribution> latest = latestPositions();
        if (latest.size() != debaters().size() || !standingCriticalObjections().isEmpty()) {
            return java.util.Optional.empty();
        }
        AgentId agreed = null;
        for (Contribution c : latest) {
            AgentId behind = behind(c);
            if (behind == null || (agreed != null && !agreed.equals(behind))) return java.util.Optional.empty();
            agreed = behind;
        }
        return java.util.Optional.ofNullable(agreed);
    }

    private AgentId behind(Contribution c) {
        if (c.position() == null) return null;
        String[] words = c.position().strip().split("[\\s:,()]+");
        String verb = words[0].toUpperCase(java.util.Locale.ROOT);
        if (verb.equals("NEW") || verb.equals("MAINTAINED") || verb.equals("REVISED")) return c.member();
        if (!verb.equals("ADOPTED")) return null;
        // The first word naming a member: "ADOPTED galen" or "ADOPTED the proposal of galen".
        for (int i = 1; i < words.length; i++) {
            String named = words[i].toLowerCase(java.util.Locale.ROOT);
            for (AgentId m : debaters()) {
                if (m.value().equals(named)
                        || m.value().replaceAll("-\\d+$", "").equals(named)) return m;
            }
        }
        return null;
    }

    /** Debate rounds held since the start or since the last record: a send-back starts a new count. */
    public int debateRoundsSinceRecord() {
        return debateRoundsAfter(recordRounds().stream().mapToInt(i -> i).max().orElse(0), Integer.MAX_VALUE);
    }

    /** Debate rounds that led to the latest record: those after the record before it. */
    public int debateRoundsBehindRecord() {
        List<Integer> records = recordRounds();
        if (records.isEmpty()) return debateRoundsSinceRecord();
        int before = records.size() > 1 ? records.get(records.size() - 2) : 0;
        return debateRoundsAfter(before, records.getLast());
    }

    private List<Integer> recordRounds() {
        return contributions.stream()
                .filter(c -> c.kind() == Kind.SYNTHESIS)
                .map(Contribution::round)
                .toList();
    }

    private int debateRoundsAfter(int after, int upTo) {
        return (int) contributions.stream()
                .filter(c -> c.kind() == Kind.REBUTTAL && c.round() > after && c.round() <= upTo)
                .mapToInt(Contribution::round)
                .distinct()
                .count();
    }

    /** The chair's latest decision record, or null before the first synthesis. */
    public Contribution synthesis() {
        return contributions.stream()
                .filter(c -> c.kind() == Kind.SYNTHESIS)
                .reduce((first, last) -> last)
                .orElse(null);
    }
}
