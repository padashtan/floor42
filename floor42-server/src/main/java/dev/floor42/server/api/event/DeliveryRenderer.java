package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.DeliveryCopy;
import dev.floor42.domain.PullRequest;
import dev.floor42.domain.UncommittedWorktree;
import dev.floor42.domain.event.DeliveryEvent;
import dev.floor42.domain.event.DeliveryEvent.DeliverablesCopied;
import dev.floor42.domain.event.DeliveryEvent.DeliverablesDestinationSet;
import dev.floor42.domain.event.DeliveryEvent.MissionAssetsSelected;
import dev.floor42.domain.event.DeliveryEvent.PluginCommandRan;
import dev.floor42.domain.event.DeliveryEvent.ProofRan;
import dev.floor42.domain.event.DeliveryEvent.ProofStepWitnessed;
import dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven;
import dev.floor42.domain.event.DeliveryEvent.PullRequestCheckState;
import dev.floor42.domain.event.DeliveryEvent.PullRequestOpened;
import dev.floor42.domain.event.DeliveryEvent.UncommittedWorkAccepted;
import dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted;
import java.util.List;
import java.util.Locale;

/** Delivery's lines: pull requests and their checks, plugin commands, and the skills a mission's sessions receive. */
final class DeliveryRenderer implements EventRenderer<DeliveryEvent> {

    @Override
    public EventLine render(Company c, DeliveryEvent event) {
        return switch (event) {
            case PullRequestOpened e -> opened(e);
            case PullRequestCheckState e -> checked(e);
            case PluginCommandRan e -> plugin(e);
            case MissionAssetsSelected e ->
                line(e.items().isEmpty() ? "none" : "some")
                        .param("mission", e.mission())
                        .param("items", e.items())
                        .param("count", e.items().size())
                        .build();
            case DeliverablesDestinationSet e ->
                line("set")
                        .param("mission", e.mission())
                        .param("folder", e.folder())
                        .build();
            case DeliverablesCopied e -> copied(e);
            case UncommittedWorkAccepted e -> uncommitted(e);
            case ProofRan e -> ProofLines.ran(e);
            case ProofStepWitnessed e -> ProofLines.witnessed(e);
            case ProofVerdictGiven e -> ProofLines.verdict(e);
            case UnprovenDeliveryAccepted e -> ProofLines.unproven(e);
        };
    }

    /** How many files were written, kept beside another, already there, or left out (ADR-0051). */
    private static EventLine copied(DeliverablesCopied e) {
        long kept = outcome(e, DeliveryCopy.KEPT_BOTH);
        long left = outcome(e, DeliveryCopy.LEFT_OUT) + e.leftOut();
        return line("copied")
                .param("mission", e.mission())
                .param("folder", e.folder())
                .param("written", outcome(e, DeliveryCopy.WRITTEN))
                .param("keptBoth", kept)
                .param("same", outcome(e, DeliveryCopy.SAME))
                .param("leftOut", left)
                .param("truncated", e.truncated())
                .build();
    }

    /** "unknown" when Floor42 could not read a worktree's state at all; "files" when it counted what was left. */
    private static EventLine uncommitted(UncommittedWorkAccepted e) {
        int files = e.worktrees().stream().mapToInt(UncommittedWorktree::count).sum();
        long unknown = e.worktrees().stream()
                .filter(w -> w.state().equals(UncommittedWorktree.UNKNOWN))
                .count();
        return line(unknown > 0 ? "unknown" : "files")
                .param("mission", e.mission())
                .param("files", files)
                .param("worktrees", e.worktrees().size())
                .param("unknown", unknown)
                .build();
    }

    private static long outcome(DeliverablesCopied e, String outcome) {
        return e.files().stream().filter(f -> f.outcome().equals(outcome)).count();
    }

    private static EventLine opened(PullRequestOpened e) {
        return line(e.found() ? "found" : "opened")
                .agent(e.workspace())
                .param("mission", e.mission())
                .param("workspace", e.workspace())
                .param("url", e.url())
                .param("number", e.number())
                .param("branch", e.branch())
                .param("base", e.base())
                .param("remote", e.remote())
                .param("found", e.found())
                .build();
    }

    /** The checks GitHub reports, counted by bucket: a cancelled check counts as failed. */
    private static EventLine checked(PullRequestCheckState e) {
        long failed = bucket(e.checks(), "fail") + bucket(e.checks(), "cancel");
        long pending = bucket(e.checks(), "pending");
        String verdict = e.checks().isEmpty() ? "none" : failed > 0 ? "failed" : pending > 0 ? "pending" : "passed";
        return line(verdict)
                .param("mission", e.mission())
                .param("number", e.number())
                .param("checks", e.checks())
                .param("count", e.checks().size())
                .param("failed", failed)
                .param("pending", pending)
                .build();
    }

    /** Where, what was asked, and what Floor42 read again afterwards: the sentence follows what is on disk. */
    private static EventLine plugin(PluginCommandRan e) {
        String outcome = e.action().equals("INSTALL")
                ? e.present() ? "installed" : "notInstalled"
                : e.present() ? "stillInstalled" : "removed";
        return line(e.target().toLowerCase(Locale.ROOT) + "." + outcome)
                .param("target", e.target())
                .param("action", e.action())
                .param("plugin", e.plugin())
                .param("marketplace", e.marketplace())
                .param("commands", e.commands())
                .param("exitCode", e.exitCode())
                .param("present", e.present())
                .param("version", e.version())
                .build();
    }

    private static long bucket(List<String> checks, String bucket) {
        return checks.stream()
                .flatMap(l -> PullRequest.Check.parse(l).stream())
                .filter(c -> c.bucket().equals(bucket))
                .count();
    }
}
