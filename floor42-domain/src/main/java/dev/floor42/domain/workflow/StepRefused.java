package dev.floor42.domain.workflow;

/**
 * A workflow refused for one of the director's steps (ADR-0052): the code the editor shows inline, and an English reason
 * for the logs and for a reader's refusal.
 */
public final class StepRefused extends IllegalArgumentException {

    private final String code;

    public StepRefused(String code, String reason) {
        super(reason);
        this.code = code;
    }

    /** {@code STEP_...} or {@code TOO_MANY_STEPS}. */
    public String code() {
        return code;
    }
}
