package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommitteeRef;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The director's decisions that need more than another look by the dispatcher: an answer to a held or failed step,
 * read from the journal, not from memory (D9), a budget raised, a decision adopted. Called by an event subscriber,
 * under the office monitor: it only reads and forks.
 */
@ApplicationScoped
public class DirectorAnswers {

    private static final Logger LOG = Logger.getLogger(DirectorAnswers.class);

    private final OfficeService office;
    private final MissionScheduler scheduler;
    private final HoldRulings rulings;
    private final Dispatcher dispatcher;
    private final Implementation implementation;
    private final MissingTools missingTools;

    @Inject
    DirectorAnswers(
            OfficeService office,
            MissionScheduler scheduler,
            HoldRulings rulings,
            Dispatcher dispatcher,
            Implementation implementation,
            MissingTools missingTools) {
        this.office = office;
        this.scheduler = scheduler;
        this.rulings = rulings;
        this.dispatcher = dispatcher;
        this.implementation = implementation;
        this.missingTools = missingTools;
    }

    /**
     * At startup, an "I install it" answer whose new measure never decided (Floor42 stopped in between) still holds its
     * mission: it is measured again now, and asks again if a tool is still absent.
     */
    void measurePendingInstalls(@Observes StartupEvent ignored) {
        for (Question q : missingTools.pendingInstalls()) {
            scheduler.fork("missing-tools-" + q.mission(), () -> {
                missingTools.answered(q);
                dispatcher.produce(q.mission());
            });
        }
    }

    /** A budget raised from the desk, while a budget question waits: the question is settled, work resumes. */
    void raisedBudget(MissionId m) {
        scheduler.fork("budget-" + m, () -> rulings.releaseBudget(m));
    }

    /** The director adopted the committee's decision and asked for it to be implemented. */
    void adoptedForImplementation(CommitteeRef committee) {
        scheduler.fork("implement-" + committee.mission(), () -> implementation.implement(committee));
    }

    /** One branch per kind of answer, the first that matches; any other answer makes the dispatcher look again. */
    void answered(MissionId m, QuestionAnswered answered) {
        Question q = office.query(c -> c.desk().find(answered.question()).orElse(null));
        if (q == null) return;
        if (q.kind() == QuestionKind.PLAN_LIMIT) {
            scheduler.fork("plan-limit-" + m, () -> rulings.ruleOnPlanLimit(m, q.codeOf(answered.answer())));
        } else if (MissingTools.is(q)) {
            scheduler.fork("missing-tools-" + m, () -> {
                missingTools.answered(q);
                dispatcher.produce(m);
            });
        } else if (q.offers(OptionCode.GO_ON_WITHOUT) && q.answered(OptionCode.DROP_TICKET)) {
            scheduler.fork("drop-" + q.ticket(), () -> drop(q));
        } else {
            // Any answer may unblock work (an approval answered after its session ended, an open question on a
            // ticket): the dispatcher looks again, and its guards decide what runs.
            scheduler.fork("resume-" + m, () -> dispatcher.produce(m));
        }
    }

    /** The director chose to drop a ticket whose dependencies were done elsewhere: it is cancelled, the mission goes on. */
    private void drop(Question q) {
        try {
            office.execute(c -> c.tickets()
                    .cancel(
                            office.context(Actor.director(), Origin.REAL),
                            q.ticket(),
                            "dropped by the director: it builds on work done on other branches"));
        } catch (DomainException e) {
            // Handled: logged; the dispatcher looks at the mission anyway
            LOG.infof("Ticket %s was not dropped: %s", q.ticket(), e.getMessage());
        }
        dispatcher.produce(q.mission());
    }
}
