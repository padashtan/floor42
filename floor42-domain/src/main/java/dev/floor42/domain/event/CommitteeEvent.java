package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A committee's deliberation and the director's ruling. Every one names its committee: the mission and the
 * committee's number in it, from 1 (ADR-0046). A row journaled before 1.8.0 has no number: a mission then had one
 * committee only, read as number 1.
 */
public sealed interface CommitteeEvent extends DomainEvent {

    MissionId mission();

    /** Never null once decoded: a row written before the field existed reads as 1. */
    Integer committeeNo();

    /** The rule of the time before numbers: a mission's only committee. */
    static Integer numbered(Integer committeeNo) {
        return committeeNo == null ? 1 : committeeNo;
    }

    /**
     * @param panel nullable before 2026-10-29: an architecture committee
     * @param moderated nullable: false
     * @param maxDebateRounds nullable: 1
     * @param directorSeat nullable: none
     * @param committeeNo nullable before 1.8.0: 1, the mission's only committee
     */
    record CommitteeConvened(
            MissionId mission,
            TicketId ticket,
            String topic,
            List<String> members,
            List<String> roles,
            String panel,
            Boolean moderated,
            Integer maxDebateRounds,
            String directorSeat,
            Integer committeeNo)
            implements CommitteeEvent {

        public CommitteeConvened {
            committeeNo = CommitteeEvent.numbered(committeeNo);

            members = members == null ? null : Collections.unmodifiableList(new ArrayList<>(members));
            roles = roles == null ? null : Collections.unmodifiableList(new ArrayList<>(roles));
        }

        public Committee.Format format() {
            return panel == null
                    ? Committee.Format.ARCHITECTURE
                    : new Committee.Format(
                            panel,
                            Boolean.TRUE.equals(moderated),
                            maxDebateRounds == null ? 1 : maxDebateRounds,
                            directorSeat == null ? null : new AgentId(directorSeat));
        }
    }

    /**
     * A member's persisted contribution. The summary fields come from the contribution's closing lines.
     *
     * @param proposal nullable
     * @param position nullable
     * @param criticalObjection nullable: none stated
     * @param byDirector nullable: false; true when the director wrote it in person, in the seat of their double
     */
    record CommitteeContributed(
            MissionId mission,
            AgentId member,
            int round,
            Committee.Kind kind,
            String text,
            String proposal,
            String position,
            String criticalObjection,
            Boolean byDirector,
            List<String> questions,
            Integer committeeNo)
            implements CommitteeEvent {
        public CommitteeContributed {
            committeeNo = CommitteeEvent.numbered(committeeNo);

            questions = questions == null ? null : Collections.unmodifiableList(new ArrayList<>(questions));
        }
    }

    /**
     * The committee's record is in and its members raised questions only the director can answer: the business analyst
     * gathers them before the director rules.
     */
    record CommitteeClarificationOpened(MissionId mission, AgentId analyst, int round, Integer committeeNo)
            implements CommitteeEvent {
        public CommitteeClarificationOpened {
            committeeNo = CommitteeEvent.numbered(committeeNo);
        }
    }

    /** The business analyst's note: what it gathered, what it settled itself, what it asks the director. */
    record CommitteeClarificationWritten(
            MissionId mission, AgentId analyst, String text, int questions, Integer committeeNo)
            implements CommitteeEvent {
        public CommitteeClarificationWritten {
            committeeNo = CommitteeEvent.numbered(committeeNo);
        }
    }

    /**
     * Every question of the clarification is answered (or there was none to ask): the answers go to the committee as
     * constraints for one more debate round; with none, the record goes to the director as it is.
     */
    record CommitteeClarified(MissionId mission, List<String> answers, Integer committeeNo) implements CommitteeEvent {
        public CommitteeClarified {
            committeeNo = CommitteeEvent.numbered(committeeNo);

            answers = answers == null ? null : Collections.unmodifiableList(new ArrayList<>(answers));
        }
    }

    /** Floor42 moves the committee on once every expected contribution of the step is in. */
    record CommitteeAdvanced(MissionId mission, Committee.Stage stage, int round, Integer committeeNo)
            implements CommitteeEvent {
        public CommitteeAdvanced {
            committeeNo = CommitteeEvent.numbered(committeeNo);
        }
    }

    /** The director sends the committee back for another round, with a constraint every member must take into account. */
    record CommitteeSentBack(MissionId mission, String constraint, Integer committeeNo) implements CommitteeEvent {
        public CommitteeSentBack {
            committeeNo = CommitteeEvent.numbered(committeeNo);
        }
    }

    /** @param overCriticalObjection the director adopted while a critical objection was still standing */
    record CommitteeArbitrated(
            MissionId mission, boolean adopted, boolean implement, boolean overCriticalObjection, Integer committeeNo)
            implements CommitteeEvent {
        public CommitteeArbitrated {
            committeeNo = CommitteeEvent.numbered(committeeNo);
        }
    }
}
