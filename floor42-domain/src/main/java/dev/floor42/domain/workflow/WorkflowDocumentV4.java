package dev.floor42.domain.workflow;

import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema version 4 of {@link WorkflowDocument} (ADR-0062), frozen once released: version 3 plus {@code "mode":"graph"}
 * at the document's level, always written, never defaulted. A loop workflow is never written in this version. The
 * steps are version 3's.
 */
final class WorkflowDocumentV4 {

    static final int MAX_LENGTH = WorkflowDocumentV3.MAX_LENGTH;

    private static final Set<String> DOCUMENT_KEYS = Set.of("name", "mode", "steps");

    private WorkflowDocumentV4() {}

    static String write(Workflow w) {
        List<String> steps = new ArrayList<>();
        for (Step s : w.steps()) steps.add(WorkflowDocumentV3.write(s));
        return "{\"name\":" + Json.quote(w.name()) + ",\"mode\":\"graph\",\"steps\":[" + String.join(",", steps) + "]}";
    }

    static Workflow read(Object root) throws Json.Refused {
        Map<String, Object> doc = WorkflowDocument.object(root, "The document", DOCUMENT_KEYS);
        if (!(doc.get("name") instanceof String name)) throw new Json.Refused("The workflow has no name");
        if (!"graph".equals(doc.get("mode"))) throw new Json.Refused("The workflow's mode is \"graph\"");
        if (!(doc.get("steps") instanceof List<?> list)) throw new Json.Refused("The workflow has no steps");
        List<Step> steps = new ArrayList<>();
        for (Object item : list) steps.add(WorkflowDocumentV3.step(item));
        return new Workflow(name, steps, Workflow.Mode.GRAPH);
    }
}
