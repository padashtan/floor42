package dev.floor42.server.app;

import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Runs a task with the runner of its kind, and decides what a failure means for the dispatcher. */
@ApplicationScoped
public class TaskRunners {

    private static final Logger LOG = Logger.getLogger(TaskRunners.class);

    private final Map<Work, TaskRunner> byWork = new EnumMap<>(Work.class);
    private final OfficeService office;
    private final RetryDesk retry;

    @Inject
    TaskRunners(@All List<TaskRunner> runners, OfficeService office, RetryDesk retry) {
        for (TaskRunner r : runners) {
            if (byWork.put(r.work(), r) != null) throw new IllegalStateException("Two runners for " + r.work());
        }
        this.office = office;
        this.retry = retry;
    }

    /**
     * Runs one task. True when the dispatcher should look again. A failure asks the director with "Retry", so the
     * mission waits instead of starting the same task again; a stop refused by the domain (the director cancelled the
     * ticket, suspended the mission) looks again only if the ticket changed meanwhile, never in a loop.
     */
    boolean run(MissionId m, Task task) {
        String before = stateOf(task.ticket());
        try {
            runnerOf(task.work()).run(m, task);
            return true;
        } catch (DomainException e) {
            // Handled: logged; a refused step is a decision, the caller sees whether it moved the ticket
            LOG.infof("Mission %s: %s on %s stopped: %s", m, task.work(), task.ticket(), e.getMessage());
            return !stateOf(task.ticket()).equals(before);
        } catch (RuntimeException e) {
            // Handled: logged; the director is asked to retry
            LOG.errorf(e, "Mission %s: %s on %s failed", m, task.work(), task.ticket());
            try {
                retry.ask(m, task.ticket(), task.agent(), "Floor42 could not run the session: " + e.getMessage());
            } catch (DomainException closed) {
                // Handled: the mission closed meanwhile (a cancel), nobody to ask; the others are woken by the caller
                LOG.infof("Mission %s: no retry asked: %s", m, closed.getMessage());
            }
            return true;
        }
    }

    /** A dependency question is not a session: it never reaches a runner. */
    private TaskRunner runnerOf(Work work) {
        TaskRunner r = byWork.get(work);
        if (r == null) throw new IllegalStateException("a question, not a task");
        return r;
    }

    private String stateOf(TicketId ticket) {
        return office.query(c -> c.tickets()
                .find(ticket)
                .map(t -> t.status() + "/" + t.assignee())
                .orElse(""));
    }
}
