package dev.floor42.server.app;

import static dev.floor42.server.app.BriefSections.clip;
import static dev.floor42.server.app.BriefSections.constraints;
import static dev.floor42.server.app.BriefSections.directorFiles;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import java.util.Locale;

/**
 * The committee's briefs (spec section 10): each member's, the chair's record, and the business analyst's questions
 * for the director. In the analysis round a member sees no other analysis: independence is real, not announced. In
 * debate rounds it sees every member's latest contribution.
 */
final class CommitteeBriefs {

    private static final int MAX_CONTRIBUTION = 6_000;

    private CommitteeBriefs() {}

    static boolean isIdea(Committee committee) {
        return CommitteeRoles.IDEA.equals(committee.format().panel());
    }

    /** A committee member's brief (spec sections 10.2 and 10.3). */
    static String member(Company c, Mission m, Committee committee, Agent agent) {
        boolean idea = isIdea(committee);
        StringBuilder s = new StringBuilder();
        s.append("You are ")
                .append(agent.displayName())
                .append(" (")
                .append(committee.roleOf(agent.id()))
                .append("), a member of the ")
                .append(idea ? "idea committee" : "architecture committee")
                .append(" of Floor42 on mission ");
        s.append(topic(m, committee, idea));
        s.append(lens(c, agent));
        s.append(workingDirectory(idea));
        if (idea) s.append(BriefTemplate.text("committee-idea-stance"));
        s.append(constraints(committee)).append(directorFiles(c, m));
        s.append(
                committee.stage() == Committee.Stage.ANALYSIS
                        ? BriefTemplate.text("committee-analysis-round")
                        : debate(committee, idea));
        s.append(BriefTemplate.text("committee-member-closing"));
        return s.append(BriefPermissions.permissions(c, m, agent, committee.ticket()))
                .toString();
    }

    /** The mission and what the committee is to prepare: the director's idea, or a decision. */
    private static String topic(Mission m, Committee committee, boolean idea) {
        return m.id() + ": " + m.title()
                + (idea
                        ? ".\n\nThe director's idea, before starting a project:\n"
                        : ".\n\nDecision to prepare, from the director:\n")
                + committee.topic() + "\n\n";
    }

    private static String lens(Company c, Agent agent) {
        CommitteeRoles.Role role = CommitteeRoles.of(agent.id(), c);
        if (role == null) return "";
        if (role.key().equals(CommitteeRoles.DOUBLE)) {
            return "Your lens: you stand in for the director and think as they do. In their own words:\n"
                    + BriefSections.directorProfile(c)
                    + "\nThe director may also speak in person in your seat: what they wrote is marked so.\n\n";
        }
        return "Your lens: " + role.lens() + "\n\n";
    }

    private static String workingDirectory(boolean idea) {
        return idea
                ? "Working directory: a git worktree of the project's repository, empty if the project does not"
                        + " exist yet. Read what you need; you change nothing.\n\n"
                : "Working directory: a git worktree of the repository. Read what you need; you change nothing.\n\n";
    }

    /** A debate round: how far it is, then every member's latest contribution. */
    private static String debate(Committee committee, boolean idea) {
        StringBuilder s = new StringBuilder("Round ").append(committee.round()).append(", debate");
        if (idea) {
            s.append(" (")
                    .append(committee.debateRoundsSinceRecord() + 1)
                    .append(" of at most ")
                    .append(committee.format().maxDebateRounds())
                    .append(": Floor42 ends the debate once every member stands behind the same proposal and no")
                    .append(" critical objection stands)");
        }
        s.append(". The members' latest contributions:\n");
        for (Committee.Contribution x : committee.latestPositions())
            s.append(contribution(committee, x, "round " + x.round()));
        return s.append(BriefTemplate.text("committee-debate-round")).toString();
    }

    /** One contribution as the others read it, cut to its first {@value #MAX_CONTRIBUTION} characters. */
    private static String contribution(Committee committee, Committee.Contribution x, String when) {
        return "\n=== " + x.member() + " (" + committee.roleOf(x.member())
                + (x.byDirector() ? ", written by the director in person" : "")
                + "), " + when + " ===\n" + clip(x.text(), MAX_CONTRIBUTION) + "\n";
    }

    /** The chair's brief: the decision record (spec section 10.4), with disagreements kept visible. */
    static String chair(Company c, Mission m, Committee committee, Agent agent) {
        boolean idea = isIdea(committee);
        StringBuilder s = new StringBuilder();
        s.append("You are ")
                .append(agent.displayName())
                .append(" (")
                .append(committee.roleOf(agent.id()))
                .append(
                        committee.format().moderated()
                                ? "), the neutral moderator of the idea committee of Floor42 on mission "
                                : "), chair of the architecture committee of Floor42 on mission ");
        s.append(topic(m, committee, idea)).append(constraints(committee)).append(directorFiles(c, m));
        if (committee.format().moderated()) s.append(moderation(committee));
        s.append("Every contribution of the committee, in order:\n");
        for (Committee.Contribution x : committee.contributions()) {
            if (x.kind() == Committee.Kind.SYNTHESIS) continue;
            s.append(contribution(committee, x, x.kind().name().toLowerCase(Locale.ROOT) + ", round " + x.round()));
        }
        s.append(BriefTemplate.text(idea ? "committee-idea-record" : "committee-decision-record"));
        s.append(BriefTemplate.text("committee-chair-closing"));
        return s.append(BriefPermissions.permissions(c, m, agent, committee.ticket()))
                .toString();
    }

    /** The moderator proposes nothing: it is told what Floor42 read of the members' agreement. */
    private static String moderation(Committee committee) {
        return "You took no part in the analyses or the debate and you take no side: you propose nothing of your own. "
                + committee
                        .agreement()
                        .map(a -> "Floor42 reads from the members' closing lines that every member stands behind " + a
                                + "'s proposal, with no critical objection standing.")
                        .orElse("Floor42 reads from the members' closing lines that they did not all agree after "
                                + committee.debateRoundsSinceRecord() + " debate round(s).")
                + "\n\n";
    }

    /**
     * The business analyst, between the committee's record and the director's ruling: the members' questions, to
     * gather, settle from the files when it can, and put to the director in business terms.
     */
    static String analyst(Company c, Mission m, Committee committee, Agent agent) {
        Committee.Contribution record = committee.synthesis();
        return BriefTemplate.named("analyst")
                        .with("name", agent.displayName())
                        .with("role", agent.role())
                        .with("mission", m.id())
                        .with("title", m.title())
                        .with("topic", committee.topic())
                        .with("objective", m.objective())
                        .with("constraints", constraints(committee))
                        .with("directorFiles", directorFiles(c, m))
                        .with(
                                "record",
                                record == null
                                        ? ""
                                        : "\nThe chair's decision record:\n---\n" + clip(record.text(), 8_000)
                                                + "\n---\n")
                        .with("questions", questions(committee))
                        .render()
                + BriefPermissions.permissions(c, m, agent, committee.ticket());
    }

    private static String questions(Committee committee) {
        StringBuilder s = new StringBuilder();
        for (Committee.Contribution x : committee.unclarifiedQuestions())
            for (String q : x.questions())
                s.append("- ")
                        .append(x.member())
                        .append(" (")
                        .append(committee.roleOf(x.member()))
                        .append("), round ")
                        .append(x.round())
                        .append(": ")
                        .append(q)
                        .append("\n");
        return s.toString();
    }
}
