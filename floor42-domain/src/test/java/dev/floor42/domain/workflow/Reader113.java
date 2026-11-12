package dev.floor42.domain.workflow;

import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The workflow reader of Floor42 1.13.0, kept to prove what a rollback reads (ADR-0052, section 4): copied from
 * {@code WorkflowDocument} at 2c69df9 (Release 1.13.0), its {@code read} and its version 1 reader verbatim; its writer
 * is left out (it reads nothing). {@link MissionWorkflow#of} at 1.13.0 turned a refused reading into
 * {@code Unrunnable}, as {@link #pin} does here.
 */
final class Reader113 {

    /** The schema version this build writes. */
    static final int CURRENT = 1;

    private Reader113() {}

    /** As 1.13.0's {@code MissionWorkflow.of}: what a pin's document reads as. */
    static MissionWorkflow pin(String document, int schemaVersion) {
        return switch (read(document, schemaVersion)) {
            case WorkflowDocument.Reading.Read r -> new MissionWorkflow.Pinned(r.workflow());
            case WorkflowDocument.Reading.Refused r -> new MissionWorkflow.Unrunnable(schemaVersion, r.reason());
        };
    }

    /** Never throws: anything this build cannot read is a reason, shown to the director. */
    static WorkflowDocument.Reading read(String document, int schemaVersion) {
        if (schemaVersion < 1)
            return new WorkflowDocument.Reading.Refused(
                    "The workflow's schema version " + schemaVersion + " is invalid");
        if (schemaVersion > CURRENT) {
            return new WorkflowDocument.Reading.Refused("Written by a newer Floor42 (workflow schema " + schemaVersion
                    + "; this build reads " + CURRENT + "): upgrade to run it");
        }
        try {
            return new WorkflowDocument.Reading.Read(V1.read(Json.parse(document)));
        } catch (Json.Refused e) {
            // Handled: the reader's own refusal becomes the reason the director reads.
            return new WorkflowDocument.Reading.Refused(e.getMessage());
        } catch (IllegalArgumentException e) {
            // Handled: Workflow's invariants and the steps' ranges refuse the document; their message is the reason.
            return new WorkflowDocument.Reading.Refused(e.getMessage());
        }
    }

    /** Schema version 1, frozen: its keys, steps, settings, ranges and defaults are literals of its own. */
    private static final class V1 {

        private static final int MAX_TICKETS = 8;

        static Workflow read(Object root) throws Json.Refused {
            Map<String, Object> doc = object(root, "The document", Set.of("name", "steps"));
            if (!(doc.get("name") instanceof String name)) throw new Json.Refused("The workflow has no name");
            if (!(doc.get("steps") instanceof List<?> list)) throw new Json.Refused("The workflow has no steps");
            List<Step> steps = new ArrayList<>();
            for (Object item : list) steps.add(step(item));
            return new Workflow(name, steps);
        }

        static Step step(Object item) throws Json.Refused {
            Map<String, Object> m = object(item, "A step", Set.of("step", "maxTickets", "autoAccept"));
            if (m.containsKey("autoAccept")) throw new Json.Refused("Auto-acceptance is not built yet");
            if (!(m.get("step") instanceof String kind)) throw new Json.Refused("A step has no kind");
            if (m.containsKey("maxTickets") && !kind.equals("plan")) {
                throw new Json.Refused("Only the plan step has maxTickets");
            }
            return switch (kind) {
                case "plan" -> new Step.Plan(maxTickets(m));
                case "approval" -> new Step.Approval();
                case "committee" -> new Step.Committee();
                case "produce" -> new Step.Produce();
                case "review" -> new Step.Review();
                case "accept" -> new Step.Accept();
                case "retro" -> new Step.Retro();
                default -> throw new Json.Refused("Unknown step \"" + kind + "\"");
            };
        }

        static int maxTickets(Map<String, Object> m) throws Json.Refused {
            Object v = m.getOrDefault("maxTickets", MAX_TICKETS);
            if (!(v instanceof Integer n) || n < 1 || n > MAX_TICKETS) {
                throw new Json.Refused("maxTickets is a number from 1 to " + MAX_TICKETS);
            }
            return n;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> object(Object value, String what, Set<String> keys) throws Json.Refused {
            if (!(value instanceof Map<?, ?> map)) throw new Json.Refused(what + " is not an object");
            for (Object k : map.keySet()) {
                if (!keys.contains(k)) throw new Json.Refused(what + " has an unknown key \"" + k + "\"");
            }
            return (Map<String, Object>) map;
        }
    }
}
