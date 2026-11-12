package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.SandboxTools;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.runtime.SandboxProbe;
import dev.floor42.server.runtime.SandboxToolkit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Says up front what the sandbox lacks for a plan, instead of a developer looping on a step that cannot run there. When
 * the planner's plan states that tickets NEED tools the sandbox's last measure found absent, Floor42 asks the director
 * ONE blocking question while the plan awaits approval: install them (the command to copy, then Floor42 measures
 * again), do those steps themselves, drop those tickets, or continue anyway. Floor42 never installs anything and never
 * widens a permission. Without the sandbox nothing is asked: the tools are then the machine's own.
 */
@ApplicationScoped
public class MissingTools {

    /** The question's phrase code: how the answer handler and the screens know it. */
    static final String CODE = "missingTools";

    static final String CODE_NO_COMMAND = "missingToolsNoCommand";

    static final List<String> OPTIONS = List.of(
            OptionCode.INSTALL_MISSING.label(),
            OptionCode.DO_IT_MYSELF.label(),
            OptionCode.DROP_TICKET.label(),
            OptionCode.RUN_ANYWAY.label());

    private static final Logger LOG = Logger.getLogger(MissingTools.class);

    private final OfficeService office;
    private final SandboxProbe probe;

    @Inject
    MissingTools(OfficeService office, SandboxProbe probe) {
        this.office = office;
        this.probe = probe;
    }

    /** Whether this question is Floor42's question about missing tools. */
    static boolean is(Question q) {
        return q.phrase() != null
                && (CODE.equals(q.phrase().code())
                        || CODE_NO_COMMAND.equals(q.phrase().code()))
                && q.offers(OptionCode.INSTALL_MISSING);
    }

    /** Runs inside the re-measure's command, before it records anything; null outside tests. */
    private volatile Runnable beforeRecord;

    /**
     * Before a plan is published: measures the sandbox once (journaled) when a ticket NEEDS an essential tool the last
     * measure says nothing about. Runs outside the office lock, while nothing of the plan exists yet: the plan and its
     * question are then recorded together ({@link #askWithin}), so the director never sees a plan without its question.
     */
    public void prepare(List<List<String>> needs) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        needs.forEach(n -> wanted.addAll(essentials(n)));
        if (wanted.isEmpty() || !office.query(MissingTools::sandboxed)) return;
        SandboxTools last = office.query(c -> c.preferences().sandboxTools().orElse(null));
        boolean unknown = wanted.stream().anyMatch(t -> SandboxToolkit.state(last, t) == SandboxToolkit.State.UNKNOWN);
        if (unknown) {
            SandboxProbe.Probe p = probe.probe(asked(wanted, last));
            office.execute(c -> record(c, p));
        }
    }

    /**
     * For tests and for a plan already recorded: prepares, then asks under the office lock. Returns whether a question
     * was asked.
     */
    public boolean check(MissionId m, Map<TicketId, List<String>> needs) {
        prepare(List.copyOf(needs.values()));
        return !office.execute(c -> askWithin(c, m, needs)).isEmpty();
    }

    /** The command names a measure asks about: the last measure's, then the essential tools wanted, never other words. */
    static List<String> asked(Collection<String> tools, SandboxTools last) {
        LinkedHashSet<String> asked = new LinkedHashSet<>();
        if (last != null) last.tools().forEach(t -> asked.add(t.name()));
        for (String t : essentials(tools)) asked.addAll(SandboxToolkit.commandsOf(t));
        return List.copyOf(asked);
    }

    /** Only the essential tools of {@link SandboxToolkit}: a name from a plan's text is never sent to the sandbox. */
    static List<String> essentials(Collection<String> tools) {
        return tools.stream()
                .map(SandboxToolkit::essential)
                .flatMap(Optional::stream)
                .map(SandboxToolkit.Essential::name)
                .distinct()
                .toList();
    }

    List<EventEnvelope> record(Company c, SandboxProbe.Probe p) {
        return record(c, p, null);
    }

    /** The measure, journaled with its cause: the question whose "I install it" answer asked for it, or null. */
    private List<EventEnvelope> record(Company c, SandboxProbe.Probe p, String cause) {
        return c.preferences()
                .recordSandboxProbe(
                        office.context(Actor.system(), Origin.REAL, cause),
                        p.distro(),
                        p.osId(),
                        p.osLike(),
                        p.osVersion(),
                        p.osName(),
                        p.measured(),
                        p.tools().stream().map(SandboxProbe.Tool::line).toList(),
                        p.reason());
    }

    /**
     * The one question's events, to be emitted inside the caller's command (under the office lock): none when the
     * sandbox is off, the mission is closed, such a question is already open, or no ticket still to do needs an absent
     * essential tool. Never throws: a question that cannot be asked is logged, the caller's command stands.
     */
    List<EventEnvelope> askWithin(Company c, MissionId m, Map<TicketId, List<String>> needs) {
        try {
            if (!sandboxed(c)) return List.of();
            if (c.missions().find(m).map(x -> x.status().isTerminal()).orElse(true)) return List.of();
            boolean open = c.desk().of(m).stream().anyMatch(q -> q.status() == QuestionStatus.OPEN && is(q));
            if (open) return List.of();
            SandboxTools last = c.preferences().sandboxTools().orElse(null);
            Map<TicketId, List<String>> absent = absent(c, last, needs);
            if (absent.isEmpty()) return List.of();
            return question(c, m, last, absent);
        } catch (DomainException e) {
            // Handled: checked before emitting (Desk.ask), so nothing was applied; the plan stands without the question
            LOG.warnf("Mission %s: the missing-tools question could not be asked: %s", m, e.getMessage());
            return List.of();
        }
    }

    /**
     * Whether the director answered "I install it" and the new measure has not decided yet: the mission waits, so no
     * trigger dispatches a ticket while the tool may still be missing.
     */
    static boolean awaitingMeasure(Company c, MissionId m) {
        return awaited(c, m).isPresent();
    }

    /** The "I install it" answer whose new measure the mission waits for, if any. */
    static Optional<Question> awaited(Company c, MissionId m) {
        Question last = latest(c, m);
        return last != null && pending(c, last) ? Optional.of(last) : Optional.empty();
    }

    private static Question latest(Company c, MissionId m) {
        Question last = null;
        for (Question q : c.desk().of(m)) if (is(q)) last = q;
        return last;
    }

    /**
     * Read from the journal, never from memory: the answer's own measure is journaled with the question as its cause,
     * in the same command as the question asked again if a tool is still absent. A question asked again is the latest
     * one, so the answered one is no longer looked at; a measure that found every tool is seen by its cause. Both hold
     * across a restart, so a settled answer is never held or measured again.
     */
    private static boolean pending(Company c, Question q) {
        if (q.status() != QuestionStatus.ANSWERED || !q.answered(OptionCode.INSTALL_MISSING)) return false;
        if (c.preferences().measuredFor(cause(q))) return false;
        return ticketsOf(q.phrase().params().get("tickets")).stream()
                .anyMatch(t -> c.tickets().find(t).map(x -> open(x.status())).orElse(false));
    }

    /** The causation id the answer's measure is journaled with. */
    static String cause(Question q) {
        return q.id().value();
    }

    /** At startup: install answers whose measure never decided (a stop in between); each is measured again. */
    List<Question> pendingInstalls() {
        return office.query(c -> c.missions().all().stream()
                .filter(x -> !x.status().isTerminal())
                .map(x -> latest(c, x.id()))
                .filter(q -> q != null && pending(c, q))
                .toList());
    }

    /** Per ticket still to do, the tools it needs that the measure found absent. */
    private static Map<TicketId, List<String>> absent(Company c, SandboxTools last, Map<TicketId, List<String>> needs) {
        Map<TicketId, List<String>> out = new LinkedHashMap<>();
        needs.forEach((id, tools) -> {
            Optional<Ticket> t = c.tickets().find(id);
            if (t.isEmpty() || !open(t.get().status())) return;
            List<String> lacking = tools.stream()
                    .filter(x -> SandboxToolkit.state(last, x) == SandboxToolkit.State.ABSENT)
                    .toList();
            if (!lacking.isEmpty()) out.put(id, lacking);
        });
        return out;
    }

    private static boolean open(TicketStatus s) {
        return s == TicketStatus.PROPOSED || s == TicketStatus.TODO;
    }

    private List<EventEnvelope> question(
            Company c, MissionId m, SandboxTools last, Map<TicketId, List<String>> absent) {
        Phrase phrase = phrase(last, absent);
        return c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        "The plan needs tools the sandbox lacks: "
                                + phrase.params().get("tools"),
                        context(phrase, absent),
                        OPTIONS,
                        null,
                        Criticality.HIGH,
                        true,
                        absent.keySet().iterator().next(),
                        phrase);
    }

    /** What the screen writes in its language: codes and language-neutral values ("T-3: chromium, pdftotext"). */
    private Phrase phrase(SandboxTools last, Map<TicketId, List<String>> absent) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        absent.values().forEach(all::addAll);
        List<String> tools = SandboxToolkit.ordered(all);
        String distro = last.distro() == null || last.distro().isBlank() ? probe.distro() : last.distro();
        Optional<String> command = SandboxToolkit.installCommandHere(distro, tools, last.osId(), last.osLike());
        List<String> each = new ArrayList<>();
        absent.forEach((id, x) -> each.add(id.value() + ": " + String.join(", ", x)));
        return Phrase.of(
                command.isPresent() ? CODE : CODE_NO_COMMAND,
                "tools",
                String.join(", ", tools),
                "tickets",
                String.join(", ", absent.keySet().stream().map(TicketId::value).toList()),
                "needs",
                String.join("; ", each),
                "distro",
                distro,
                "measured",
                last.measuredAt() == null ? "" : last.measuredAt().toString(),
                "command",
                command.orElse(""));
    }

    /** The journal's English sentence, from the phrase's values. */
    private static String context(Phrase phrase, Map<TicketId, List<String>> absent) {
        Map<String, String> p = phrase.params();
        List<String> sentence = new ArrayList<>();
        absent.forEach((id, x) -> sentence.add(id.value() + " needs " + String.join(", ", x)));
        String command = p.get("command");
        return String.join("; ", sentence) + ". The sandbox (" + p.get("distro") + ") does not have "
                + (p.get("tools").contains(",") ? "them" : "it")
                + " (measured " + p.get("measured") + ") and Floor42 installs nothing. "
                + (command.isEmpty()
                        ? "Floor42 has no package for it here: install it yourself, then Floor42 measures the sandbox"
                                + " again. "
                        : "To install it yourself, copy this into a Windows terminal: " + command
                                + ". Floor42 then measures the sandbox again. ")
                + "Or do those steps yourself (the developer does the rest, its review comes to you), drop "
                + p.get("tickets") + ", or continue anyway: the developer then reports the step it could not do.";
    }

    /**
     * The director answered: installed (Floor42 measures again, and asks again only if a tool is still absent), drops
     * the tickets named, or goes on (doing the step, or anyway). The dispatcher looks at the mission afterwards.
     */
    void answered(Question q) {
        OptionCode code = q.answerCode();
        Map<String, String> params = q.phrase().params();
        List<TicketId> tickets = ticketsOf(params.get("tickets"));
        if (code == OptionCode.INSTALL_MISSING) {
            measureAgain(q, tickets, essentials(splitList(params.get("tools"))));
        } else if (code == OptionCode.DROP_TICKET) {
            for (TicketId t : tickets) drop(t);
        }
    }

    /** "I install it": the sandbox measured again, and the question asked again if a tool is still absent. */
    private void measureAgain(Question q, List<TicketId> tickets, List<String> tools) {
        SandboxTools last = office.query(c -> c.preferences().sandboxTools().orElse(null));
        SandboxProbe.Probe p = probe.probe(asked(tools, last));
        Map<TicketId, List<String>> needs = new LinkedHashMap<>();
        for (TicketId t : tickets) needs.put(t, tools);
        // The new measure and the question asked again (if a tool is still absent) are one command under the office
        // lock: the hold lifts only once that command is journaled, never in between, and two measures of the same
        // answer (an answer and the startup) ask again at most once (askWithin's open-question check, same command).
        try {
            office.execute(c -> {
                Runnable hook = beforeRecord;
                if (hook != null) hook.run();
                List<EventEnvelope> all = new ArrayList<>(record(c, p, cause(q)));
                all.addAll(askWithin(c, q.mission(), needs));
                return all;
            });
        } catch (RuntimeException e) {
            // Handled: nothing was recorded, so the mission is still held; the director is asked again rather than
            // left with a hold no question explains (and the stall diagnosis names it if even that fails)
            LOG.warnf(e, "Mission %s: the measure after \"I install it\" could not be recorded", q.mission());
            askAgain(q.mission(), needs);
        }
    }

    /** The question asked again from the last measure, after the answer's own measure could not be recorded. */
    private void askAgain(MissionId m, Map<TicketId, List<String>> needs) {
        try {
            office.execute(c -> askWithin(c, m, needs));
        } catch (RuntimeException e) {
            // Handled: logged; the mission stays held and the stall diagnosis shows it (SANDBOX_MEASURE)
            LOG.warnf(e, "Mission %s: the missing-tools question could not be asked again", m);
        }
    }

    /** For tests: runs inside the re-measure's command, before it records; a throw there is a failed command. */
    void beforeRecord(Runnable hook) {
        beforeRecord = hook;
    }

    private void drop(TicketId t) {
        try {
            office.execute(c -> c.tickets()
                    .cancel(
                            office.context(Actor.director(), Origin.REAL),
                            t,
                            "dropped by the director: it needs a tool the sandbox lacks"));
        } catch (DomainException e) {
            // Handled: logged; a ticket already done or cancelled stays as it is
            LOG.infof("Ticket %s was not dropped: %s", t, e.getMessage());
        }
    }

    private static List<TicketId> ticketsOf(String list) {
        return splitList(list).stream().map(TicketId::new).toList();
    }

    private static List<String> splitList(String list) {
        if (list == null || list.isBlank()) return List.of();
        return java.util.Arrays.stream(list.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean sandboxed(Company c) {
        return OfficeSettings.sandboxed(c);
    }
}
