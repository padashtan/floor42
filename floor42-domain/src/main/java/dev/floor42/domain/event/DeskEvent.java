package dev.floor42.domain.event;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Questions to the director and the approvals they remember. */
public sealed interface DeskEvent extends DomainEvent {

    /**
     * @param askedBy nullable when Floor42 itself asks (escalation)
     * @param recommendation nullable
     * @param ticket nullable
     * @param action nullable: set when the question is an approval request for that action
     * @param optionCodes the {@link dev.floor42.domain.OptionCode} of each option, by position, null where the option is
     *     not one of Floor42's own; the whole list is absent (null) in a question journaled before 1.6.0, whose options
     *     are then read by their label (ADR-0009, amendment 2026-10-31)
     */
    record QuestionAsked(
            QuestionId question,
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
            dev.floor42.domain.Phrase phrase,
            List<String> optionCodes)
            implements DeskEvent {
        public QuestionAsked {
            optionCodes = optionCodes == null ? null : Collections.unmodifiableList(new ArrayList<>(optionCodes));
            options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
        }
    }

    record QuestionAnswered(QuestionId question, String answer) implements DeskEvent {}

    record QuestionWithdrawn(QuestionId question, String reason) implements DeskEvent {}

    /**
     * An approval whose answer no longer decides other requests: forgotten by the director, or asked for one call
     * only (recorded with the question, so a one-off answer is never reused).
     */
    record ApprovalForgotten(QuestionId question, String reason) implements DeskEvent {}
}
