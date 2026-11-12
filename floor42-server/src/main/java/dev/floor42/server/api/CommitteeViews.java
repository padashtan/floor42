package dev.floor42.server.api;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read models of a mission's committee: its members, every contribution, and what still stands. */
public final class CommitteeViews {

    private CommitteeViews() {}

    /**
     * One committee of the mission's history, in a line.
     *
     * @param recommendation the latest decision record's recommendation, or null before one or when not stated
     */
    public record CommitteeSummaryView(
            int number, String topic, String ticket, String stage, String recommendation, boolean implement) {
        static CommitteeSummaryView of(Committee c) {
            Committee.Contribution record = c.synthesis();
            return new CommitteeSummaryView(
                    c.number(),
                    c.topic(),
                    c.ticket().value(),
                    c.stage().name(),
                    record == null ? null : record.proposal(),
                    c.implement());
        }
    }

    public record MemberView(String agent, String role, boolean chair, boolean pending) {
        static MemberView of(Committee c, AgentId a) {
            return new MemberView(
                    a.value(), c.roleOf(a), a.equals(c.chair()), c.pending().contains(a));
        }
    }

    public record ContributionView(
            String member,
            String role,
            int round,
            String kind,
            String text,
            String proposal,
            String position,
            String criticalObjection,
            String at,
            boolean byDirector,
            List<String> questions) {
        public ContributionView {
            questions = questions == null ? null : Collections.unmodifiableList(new ArrayList<>(questions));
        }

        static ContributionView of(Committee c, Committee.Contribution x) {
            return new ContributionView(
                    x.member().value(),
                    c.roleOf(x.member()),
                    x.round(),
                    x.kind().name(),
                    x.text(),
                    x.proposal(),
                    x.position(),
                    x.criticalObjection(),
                    x.at().toString(),
                    x.byDirector(),
                    x.questions() == null ? List.of() : x.questions());
        }
    }

    /**
     * A committee as persisted: members, every contribution, and what still stands.
     *
     * @param panel architecture or idea
     * @param directorSeat the member the director may speak for in person, or null
     * @param agreement the member every debater stands behind, read from their closing lines; null when they do not
     *     all agree
     * @param debateRounds debate rounds held since the start or the last record
     * @param analyst the business analyst of the clarification under way, or null
     * @param clarificationNote the business analyst's latest note, or null
     * @param clarifications how many times the committee went through the business analyst
     * @param number the committee's number in the mission, from 1 (ADR-0046)
     */
    public record CommitteeView(
            String topic,
            String ticket,
            String stage,
            int round,
            List<MemberView> members,
            List<ContributionView> contributions,
            List<String> constraints,
            List<ContributionView> standingCriticalObjections,
            boolean implement,
            String panel,
            boolean moderated,
            int maxDebateRounds,
            String directorSeat,
            String agreement,
            int debateRounds,
            String analyst,
            String clarificationNote,
            int clarifications,
            int number) {
        public CommitteeView {
            constraints = constraints == null ? null : Collections.unmodifiableList(new ArrayList<>(constraints));
            contributions = contributions == null ? null : Collections.unmodifiableList(new ArrayList<>(contributions));
            members = members == null ? null : Collections.unmodifiableList(new ArrayList<>(members));
            standingCriticalObjections =
                    standingCriticalObjections == null ? null : List.copyOf(standingCriticalObjections);
        }

        static CommitteeView of(Committee c) {
            return new CommitteeView(
                    c.topic(),
                    c.ticket().value(),
                    c.stage().name(),
                    c.round(),
                    c.memberIds().stream().map(a -> MemberView.of(c, a)).toList(),
                    contributions(c, c.contributions()),
                    c.constraints(),
                    contributions(c, c.standingCriticalObjections()),
                    c.implement(),
                    c.format().panel(),
                    c.format().moderated(),
                    c.format().maxDebateRounds(),
                    c.format().directorSeat() == null
                            ? null
                            : c.format().directorSeat().value(),
                    c.agreement().map(AgentId::value).orElse(null),
                    c.debateRoundsSinceRecord(),
                    c.analyst() == null ? null : c.analyst().value(),
                    c.clarificationNote(),
                    c.clarifications(),
                    c.number());
        }

        private static List<ContributionView> contributions(Committee c, List<Committee.Contribution> list) {
            return list.stream().map(x -> ContributionView.of(c, x)).toList();
        }
    }
}
