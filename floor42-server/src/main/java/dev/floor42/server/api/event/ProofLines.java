package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.ProofStep;
import dev.floor42.domain.event.DeliveryEvent.ProofRan;
import dev.floor42.domain.event.DeliveryEvent.ProofStepWitnessed;
import dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven;
import dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted;

/** The lines of a delivery's proof (ADR-0058), for DeliveryRenderer. */
final class ProofLines {

    private ProofLines() {}

    /** "failed" when a step Floor42 ran failed, "passed" when some passed, else "notRun"; counts of each outcome. */
    static EventLine ran(ProofRan e) {
        long failed = count(e, "FAILED");
        long passed = count(e, "PASSED");
        return line(failed > 0 ? "failed" : passed > 0 ? "passed" : "notRun")
                .agent(e.target().workspace())
                .param("agent", e.target().workspace())
                .param("mission", e.mission())
                .param("passed", passed)
                .param("failed", failed)
                .param(
                        "director",
                        e.steps().stream()
                                .filter(s -> s.runner().equals("DIRECTOR"))
                                .count())
                .build();
    }

    static EventLine witnessed(ProofStepWitnessed e) {
        return line(e.works() ? "works" : "fails")
                .agent(e.target().workspace())
                .param("agent", e.target().workspace())
                .param("mission", e.mission())
                .param("step", e.step())
                .build();
    }

    static EventLine verdict(ProofVerdictGiven e) {
        return line(e.verdict().equals("PROVEN") ? "proven" : "notProven")
                .param("mission", e.mission())
                .build();
    }

    static EventLine unproven(UnprovenDeliveryAccepted e) {
        return line(e.state()).param("mission", e.mission()).build();
    }

    private static long count(ProofRan e, String outcome) {
        return e.steps().stream()
                .map(ProofStep::outcome)
                .filter(outcome::equals)
                .count();
    }
}
