package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.ProofViews.ProofRunView;
import dev.floor42.server.app.ProofViews.ProofStepResult;
import dev.floor42.server.app.ProofViews.ProofWitnessView;
import dev.floor42.server.app.ProofViews.ProofWorktree;
import dev.floor42.server.app.ProofViews.SentBack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * "Send back to the producer" (ADR-0058): the ticket the agent whose recipe failed produced goes back to work, in the
 * same mission and the same worktree, with the evidence as a directive ({@code DirectiveGiven}, an existing event; the
 * director as actor, the mission's origin). Its review starts again after the fix. A new ticket would wait behind the
 * agent's own unaccepted work in the workshop (HeldWork); a directive resumes it.
 */
@ApplicationScoped
public class ProofSendBack {

    static final int MAX_NOTE = 4_000;
    static final int OUTPUT_IN_TICKET = 1_500;

    @Inject
    OfficeService office;

    @Inject
    DeliveryProofs proofs;

    @Inject
    ProofReader reader;

    /**
     * @param note what the director saw when they ran their own steps; required when no step Floor42 ran failed
     */
    public SentBack sendBack(MissionId id, String agent, String note) {
        Mission m = reader.mission(id);
        ProofReader.Current c = proofs.producer(id, agent);
        ProofWorktree w = proofs.view(id).worktrees().stream()
                .filter(x -> x.agent().equals(agent))
                .findFirst()
                .orElseThrow();
        String said = note == null ? "" : note.strip();
        if (said.length() > MAX_NOTE)
            throw new DomainException("INVALID_ARGUMENT", "The note is longer than " + MAX_NOTE + " characters");
        ProofRunView run = w.lastRun();
        List<ProofStepResult> failed = run == null || run.stale()
                ? List.of()
                : run.steps().stream().filter(s -> s.outcome().equals("FAILED")).toList();
        String seen = witnessed(c.recipe(), w.witnesses());
        if (failed.isEmpty() && seen.isEmpty() && said.isEmpty())
            throw new DomainException("NOTHING_TO_SEND_BACK", "No step failed: say what you saw when you ran yours");
        String directive = evidence(c.recipe(), failed, (seen + "\n" + said).strip());
        CommandContext director = office.context(Actor.director(), m.origin());
        TicketId produced = office.query(q -> producedBy(q, id, c.workspace().agent()));
        TicketId to = office.query(q -> q.workshop().checkDirective(director, produced));
        office.execute(q -> q.workshop().giveDirective(director, produced, directive));
        return new SentBack(to.value(), agent);
    }

    /** The director's steps that did not work, with what they saw, as recorded. */
    static String witnessed(ProofRecipe recipe, List<ProofWitnessView> said) {
        StringBuilder b = new StringBuilder();
        for (ProofWitnessView v : said) {
            if (v.works()) continue;
            ProofRecipe.Step s = step(recipe, v.step());
            b.append("Step ").append(v.step());
            if (s != null) b.append(" \"").append(s.title()).append("\": ").append(s.command());
            b.append("\nDoes not work. Seen: ").append(v.seen()).append("\n");
        }
        return b.toString().strip();
    }
    /** The ticket the agent produced last: its latest done work ticket, else its latest work ticket not dropped. */
    static TicketId producedBy(Company c, MissionId id, AgentId agent) {
        List<Ticket> mine = c.tickets().of(id).stream()
                .filter(t -> t.kind() == TicketKind.WORK && agent.equals(t.assignee()))
                .filter(t -> t.status() != TicketStatus.CANCELLED)
                .toList();
        return mine.stream()
                .filter(t -> t.status() == TicketStatus.DONE)
                .reduce((a, b) -> b)
                .or(() -> mine.stream().reduce((a, b) -> b))
                .map(Ticket::id)
                .orElseThrow(() -> new DomainException("UNKNOWN_TICKET", agent + " produced no ticket on " + id));
    }

    /** The ticket's description: each failed step with what was expected and what Floor42 measured, then the director. */
    static String evidence(ProofRecipe recipe, List<ProofStepResult> failed, String said) {
        StringBuilder b = new StringBuilder("The director's proof of the delivery found a problem (ADR-0058). Fix it,"
                + " then keep PROVE.md at the worktree's root so that it proves the fix.\n");
        for (ProofStepResult r : failed) {
            ProofRecipe.Step s = step(recipe, r.index());
            b.append("\nStep ").append(r.index());
            if (s != null) b.append(" \"").append(s.title()).append('"');
            b.append(": ").append(r.command()).append('\n');
            if (s != null) b.append("Expected: ").append(expected(s)).append('\n');
            b.append("Floor42 measured in the sandbox, network off: ")
                    .append(r.timedOut() ? "stopped at the timeout" : "exit " + r.exitCode())
                    .append(" (")
                    .append(r.failure())
                    .append(")\nEnd of the output:\n")
                    .append(tail(r.output()))
                    .append('\n');
        }
        if (!said.isEmpty())
            b.append("\nThe director ran it and says:\n").append(said).append('\n');
        return b.toString();
    }

    private static String expected(ProofRecipe.Step s) {
        List<String> parts = new ArrayList<>();
        if (s.expectExit() != null) parts.add("exit " + s.expectExit());
        s.expectOutput().forEach(o -> parts.add("output containing " + o));
        return String.join("; ", parts);
    }

    private static String tail(String output) {
        return output.length() <= OUTPUT_IN_TICKET
                ? output
                : "…" + output.substring(output.length() - OUTPUT_IN_TICKET);
    }

    private static ProofRecipe.Step step(ProofRecipe recipe, int index) {
        return recipe.steps().stream()
                .filter(s -> s.index() == index)
                .findFirst()
                .orElse(null);
    }
}
