package dev.floor42.server.app;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.Decision;
import dev.floor42.domain.Mission;
import dev.floor42.domain.SandboxTools;
import dev.floor42.domain.Setting;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.ToolCatalog;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What an agent may do and where it runs, as its brief states it: the permission table Floor42 enforces (the brief
 * only informs, the hooks decide), the shell sandbox and its measured tools, and the language of the prose.
 */
final class BriefPermissions {

    private BriefPermissions() {}

    /** Tools worth offering at all outside any ticket: those the mission does not forbid for everyone. */
    static List<String> offeredTools(Mission m) {
        return offeredTools(m, null);
    }

    /**
     * Tools worth offering to a session on this ticket: those neither the mission nor the ticket forbids for everyone.
     * A ticket's own rule may open a tool the mission keeps closed (D15).
     */
    static List<String> offeredTools(Mission m, TicketId ticket) {
        return ToolCatalog.GOVERNED_TOOLS.stream()
                .filter(tool -> m.policy()
                                .evaluate(null, ticket, ToolCatalog.category(tool), tool, null)
                                .decision()
                        != Decision.DENY)
                .toList();
    }

    /**
     * What applies to this agent on this ticket: rules naming it are more specific than a ticket's rules, which are
     * more specific than the mission-wide table.
     */
    static String permissions(Company c, Mission m, Agent agent, TicketId ticket) {
        boolean reviewing = reviewShell(m, agent, ticket);
        String lines = ToolCatalog.GOVERNED_TOOLS.stream()
                .map(tool -> line(m, agent, ticket, tool))
                .collect(Collectors.joining("\n"));
        return "Permissions, enforced by Floor42 (not by you). A refused call will not succeed if retried: adapt.\n"
                + lines
                + "\n- ask_director (Floor42's own tool): asks the director a question; your session waits for the"
                + " answer. Only for a decision that is the director's to make; otherwise decide and say so."
                + sandboxNote(c, reviewing)
                + (reviewing ? REVIEW_SHELL : "");
    }

    /** A reviewer's Bash line on this ticket (MissionPermissions.allowReviewShell): its shell runs read-only, offline. */
    private static boolean reviewShell(Mission m, Agent agent, TicketId ticket) {
        return ticket != null
                && m.policy().rules().stream()
                        .anyMatch(r -> agent.id().equals(r.agent())
                                && ticket.equals(r.ticket())
                                && "Bash".equals(r.tool())
                                && r.decision() == Decision.ALLOW);
    }

    /** What a reviewer's shell is (ADR-0046, amended 2026-11-05): the approved tests rerun, nothing written, no network. */
    private static final String REVIEW_SHELL =
            "\n- Review shell: your worktree is mounted READ-ONLY in the sandbox and the network is cut for you. The"
                    + " dependencies installed in the worktree are there, read-only. You may run the approved test"
                    + " commands (the node's tests, the mission's checks command) to verify the deliverable, in a copy:"
                    + " cp -r . /tmp/work && cd /tmp/work (only /tmp is writable, and it is emptied after each"
                    + " command). Do not try to change a file of the work: you review, you never write. Report what a"
                    + " rerun showed, and say so when a command could not run.";

    private static String line(Mission m, Agent agent, TicketId ticket, String tool) {
        ActionCategory category = ToolCatalog.category(tool);
        Decision d =
                m.policy().evaluate(agent.id(), ticket, category, tool, null).decision();
        return "- " + tool + ": " + decision(d, category) + note(tool, d);
    }

    private static String decision(Decision d, ActionCategory category) {
        return switch (d) {
            case ALLOW -> "runs freely";
            case ASK ->
                "asks the director the first time"
                        + (category == ActionCategory.EXECUTE ? " for each exact command" : "");
            case ASK_ALWAYS -> "asks the director every time";
            case DENY -> "forbidden";
        };
    }

    private static String note(String tool, Decision d) {
        if (d == Decision.DENY) return "";
        return switch (tool) {
            case "Skill" -> " (loads one of the skills listed to you)";
            case "Agent" -> " (a subagent's own calls follow this same table)";
            default -> "";
        };
    }

    /** What the shell is like when it runs in the sandbox (ADR-0028): agents should not try Windows paths there. */
    private static String sandboxNote(Company c, boolean offline) {
        if (!OfficeSettings.sandboxed(c)) return "";
        boolean net = !offline && "on".equals(setting(c, Setting.SANDBOX_NETWORK));
        return "\n- Your Bash commands run in an isolated Linux sandbox: the current directory is your worktree,"
                + " mounted at /media/work; use relative paths. Nothing else of the machine is visible, and programs"
                + " of the host cannot start there. The network is " + (net ? "open" : "cut") + ". The tools are those"
                + " installed in the sandbox: if one is missing, say so instead of working around it."
                + measuredTools(c);
    }

    private static String setting(Company c, Setting setting) {
        return c.preferences().setting(setting).orElse("off");
    }

    /** The tools Floor42 last measured present in the sandbox (ADR-0033); nothing when no measure holds. */
    static String measuredTools(Company c) {
        Optional<SandboxTools> last = c.preferences().sandboxTools().filter(SandboxTools::measured);
        if (last.isEmpty()) return "";
        List<String> present = last.get().tools().stream()
                .filter(SandboxTools.Tool::present)
                .map(t -> t.name() + (t.version().isBlank() ? "" : " (" + t.version() + ")"))
                .toList();
        String missing = last.get().tools().stream()
                .filter(t -> !t.present())
                .map(SandboxTools.Tool::name)
                .collect(Collectors.joining(", "));
        return " Measured by Floor42 in the sandbox on " + last.get().measuredAt() + ": "
                + (present.isEmpty() ? "no tool found" : "present " + String.join(", ", present))
                + (missing.isEmpty() ? "" : "; not installed: " + missing)
                + ". HOME is an empty folder there, so ~/.m2, ~/.npm and the like start empty at each command.";
    }

    /**
     * The brief in the language the director chose for the agents. The marker lines Floor42 reads stay in English:
     * they are parsed, not read by a person.
     */
    static String inLanguage(Company c, String brief) {
        if (!"fr".equals(c.preferences().setting(Setting.AGENT_LANGUAGE).orElse("en"))) return brief;
        return brief
                + "\nLanguage: write your prose in French (reports, findings, contributions, questions to the director)."
                + " Keep every marker line Floor42 reads exactly as asked above, in English (VERDICT, PROPOSAL,"
                + " POSITION, CRITICAL OBJECTION, TICKET, WHY, DONE WHEN, DEPENDS ON, SIZE, CONSULT, RESEARCH, WENT WELL,"
                + " WENT BADLY, CONCLUSION, LESSON, BLOCKED BY ENVIRONMENT), and keep code, paths and commands as they are.\n";
    }
}
