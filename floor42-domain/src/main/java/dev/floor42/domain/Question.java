package dev.floor42.domain;

import java.time.Instant;
import java.util.List;

/** Something only the director can decide. Blocking questions suspend the work that depends on them. */
public final class Question {

    private final QuestionId id;
    private final MissionId mission;
    private final QuestionKind kind;
    private final AgentId askedBy;
    private final String subject;
    private final String context;
    private final List<String> options;
    private final String recommendation;
    private final Criticality criticality;
    private final boolean blocking;
    private final TicketId ticket;
    private final ActionId action;
    private final Instant askedAt;
    private final Phrase phrase;
    /** Null for a question journaled before option codes existed: its options are read by their label. */
    private final List<String> optionCodes;

    QuestionStatus status = QuestionStatus.OPEN;
    String answer;

    Question(
            QuestionId id,
            MissionId mission,
            QuestionKind kind,
            AgentId askedBy,
            String subject,
            String context,
            List<String> options,
            String recommendation,
            Criticality criticality,
            boolean blocking,
            TicketId ticket,
            ActionId action,
            Instant askedAt,
            Phrase phrase,
            List<String> optionCodes) {
        this.id = id;
        this.mission = mission;
        this.kind = kind;
        this.askedBy = askedBy;
        this.subject = subject;
        this.context = context;
        this.options = List.copyOf(options);
        this.recommendation = recommendation;
        this.criticality = criticality;
        this.blocking = blocking;
        this.ticket = ticket;
        this.action = action;
        this.askedAt = askedAt;
        this.phrase = phrase;
        this.optionCodes = optionCodes == null || optionCodes.size() != this.options.size()
                ? null
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(optionCodes));
    }

    /**
     * What an option means, by its stable code: the code recorded with the question, or, for a question journaled
     * before codes existed, the option read by its label. Null for an option that is not one of Floor42's own.
     */
    public OptionCode codeOf(String option) {
        int i = option == null ? -1 : options.indexOf(option);
        if (i < 0) return null;
        if (optionCodes == null) return OptionCode.byLabel(option);
        String code = optionCodes.get(i);
        if (code == null) return null;
        try {
            return OptionCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            // Handled: a code this version does not know (an older journal's label) reads as no code
            return null;
        }
    }

    /** Whether the question offers this option. */
    public boolean offers(OptionCode code) {
        return options.stream().anyMatch(o -> codeOf(o) == code);
    }

    /** The code of the answer given; null while open, or for an answer that is not one of Floor42's options. */
    public OptionCode answerCode() {
        return answer == null ? null : codeOf(answer);
    }

    /** Whether the director answered this option. */
    public boolean answered(OptionCode code) {
        return answerCode() == code;
    }

    /** The option of this code, as labelled in this question; null when it does not offer it. */
    public String optionOf(OptionCode code) {
        return options.stream().filter(o -> codeOf(o) == code).findFirst().orElse(null);
    }

    /** Null for a question written by an agent, or recorded before Floor42 kept one. */
    public Phrase phrase() {
        return phrase;
    }

    public QuestionId id() {
        return id;
    }

    public MissionId mission() {
        return mission;
    }

    public QuestionKind kind() {
        return kind;
    }

    /** Null when Floor42 itself escalates. */
    public AgentId askedBy() {
        return askedBy;
    }

    public String subject() {
        return subject;
    }

    public String context() {
        return context;
    }

    /** Empty for open questions. */
    public List<String> options() {
        return options;
    }

    public String recommendation() {
        return recommendation;
    }

    public Criticality criticality() {
        return criticality;
    }

    public boolean blocking() {
        return blocking;
    }

    public TicketId ticket() {
        return ticket;
    }

    /** Set when the question is an approval request for this action. */
    public ActionId action() {
        return action;
    }

    public Instant askedAt() {
        return askedAt;
    }

    public QuestionStatus status() {
        return status;
    }

    public String answer() {
        return answer;
    }
}
