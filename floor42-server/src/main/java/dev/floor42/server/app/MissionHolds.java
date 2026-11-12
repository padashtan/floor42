package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PlanUsage;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * What holds a mission back for the director, enforced by code before every session (spec section 15): the budget,
 * then the plan's usage windows. The questions that hold it are read from the journal (D9); two of the director's
 * answers live in memory only, as they are one-shot or timed.
 */
@ApplicationScoped
public class MissionHolds {

    private static final Logger LOG = Logger.getLogger(MissionHolds.class);

    /** How long a "Run anyway" covers the mission: every task the dispatcher starts meanwhile runs. */
    static final Duration RUN_ANYWAY_FOR = Duration.ofMinutes(5);

    private final OfficeService office;
    private final OfficeSettings settings;

    /**
     * Missions the director let run once past the plan-limit check. One-shot: lost on a restart, the check then asks
     * again, which is the safe side.
     */
    private final Map<MissionId, Instant> runAnyway = new ConcurrentHashMap<>();

    /**
     * Missions the director chose to rest until a usage window resets: nothing starts on them meanwhile, so the
     * question is not asked again. The answer is in the journal; recovery rests again after a restart.
     */
    private final Map<MissionId, Instant> resting = new ConcurrentHashMap<>();

    @Inject
    MissionHolds(OfficeService office, OfficeSettings settings) {
        this.office = office;
        this.settings = settings;
    }

    /**
     * True when the session must not start: the budget is spent, or the plan's usage window would likely cut it. The
     * director is asked (wait for the reset, run anyway, or stop); the held step is not kept, the mission resumes from
     * what the journal says is next. A session already running is never cut short: its cost is known only at its end.
     */
    boolean held(MissionId m, String model) {
        if (holdForBudget(m)) return true;
        Instant until = runAnyway.get(m);
        if (until != null && until.isAfter(Instant.now())) return false;
        Optional<PlanUsage.Risk> risk =
                office.query(c -> c.verification().planLimitRisk(model, settings.planLimitThreshold(), Instant.now()));
        if (risk.isEmpty()) return false;
        office.execute(c ->
                c.verification().reportPlanLimit(office.context(Actor.system(), Origin.REAL), m, model, risk.get()));
        LOG.infof("Mission %s held: %s usage window at risk", m, risk.get().window());
        return true;
    }

    private boolean holdForBudget(MissionId m) {
        if (!office.query(c -> c.verification().budgetReached(m))) return false;
        office.execute(c -> c.verification().reportBudgetReached(office.context(Actor.system(), Origin.REAL), m));
        LOG.infof("Mission %s held: budget reached", m);
        return true;
    }

    /** The director answered "Run anyway": the plan-limit check lets the mission run for a while. */
    void runAnyway(MissionId m) {
        runAnyway.put(m, Instant.now().plus(RUN_ANYWAY_FOR));
    }

    /** Until when the mission rests, or null. */
    Instant restingUntil(MissionId m) {
        return resting.get(m);
    }

    /** Rests the mission until this time, as "Wait for the reset" does: nothing starts on it meanwhile; null ends it. */
    void restUntil(MissionId m, Instant until) {
        if (until == null) resting.remove(m);
        else resting.put(m, until);
    }

    /**
     * The mission waits for the director: a budget or plan-limit hold, a failed session, or an escalated review is
     * still open. Nothing runs meanwhile, whoever asks.
     */
    static boolean waiting(Company c, MissionId m) {
        return c.desk().of(m).stream().anyMatch(MissionHolds::holdsMission) || MissingTools.awaitingMeasure(c, m);
    }

    /** An open question that holds the whole mission, not only its ticket. */
    static boolean holdsMission(Question q) {
        return q.status() == QuestionStatus.OPEN
                && (q.kind() == QuestionKind.BUDGET_REACHED
                        || q.kind() == QuestionKind.PLAN_LIMIT
                        || q.kind() == QuestionKind.REVIEW_ESCALATION
                        || q.offers(OptionCode.RETRY)
                        || MissingTools.is(q));
    }
}
