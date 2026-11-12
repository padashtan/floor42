package dev.floor42.server.api;

import dev.floor42.domain.Company;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.PreferenceEvent.SettingChanged;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import dev.floor42.domain.event.WorkbenchEvent.DirectorEditedFile;
import dev.floor42.server.security.ReplaySanitizer;
import java.util.Set;

/** The events of a replay file, one by one: each line written from the English catalogue, outputs cleaned. */
final class ReplayEvents {

    private ReplayEvents() {}

    /** One event of the file, its agent noted; the line is written here, from the English catalogue. */
    static ReplayExports.EventPart part(
            Company c, EventEnvelope e, int seq, ReplaySanitizer clean, Set<String> agents) {
        EventView v = EventView.of(c, e);
        // The file is read without Floor42's screen.
        String text = dev.floor42.server.api.event.EnglishLines.of(v.code(), v.params());
        String output = null;
        ReplayExports.Cost cost = null;
        switch (e.event()) {
            case SettingChanged s -> text = withheldSetting(s, seq, clean);
            case DirectorEditedFile _ -> clean.withheld(seq, "diff", ReplaySanitizer.Kind.DIRECTOR_FILE);
            case ActionExecuted x -> output = clean.output(seq, "output", x.output());
            case ChecksRan r -> output = clean.output(seq, "output", r.output());
            case AgentSessionEnded s -> {
                // A simulated session paid nothing: its line says so, its recording's estimate is not exported.
                if (e.origin() == Origin.REAL && s.costUsdEstimated() != null)
                    cost = new ReplayExports.Cost(s.costUsdEstimated(), "estimated");
            }
            case AgentSessionStarted s -> agents.add(s.agent().value());
            default -> {}
        }
        if (v.agent() != null) agents.add(v.agent());
        return eventPart(e, v, seq, clean, text, output, cost);
    }

    private static ReplayExports.EventPart eventPart(
            EventEnvelope e,
            EventView v,
            int seq,
            ReplaySanitizer clean,
            String text,
            String output,
            ReplayExports.Cost cost) {
        boolean none = output == null || output.isEmpty();
        return new ReplayExports.EventPart(
                seq,
                e.occurredAt().toString(),
                e.origin().name(),
                clean.text(seq, "actor", v.actor()),
                e.type(),
                clean.text(seq, "text", text),
                v.agent(),
                v.ticket(),
                v.tool(),
                v.ran(),
                none ? null : output,
                none ? null : ReplaySanitizer.clipped(output),
                cost);
    }

    /** A setting's value never leaves the machine, whatever it is. */
    private static String withheldSetting(SettingChanged s, int seq, ReplaySanitizer clean) {
        clean.withheld(seq, "value", ReplaySanitizer.Kind.SETTING_VALUE);
        return "Director changed the setting "
                + s.setting().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                + " (value not exported)";
    }
}
