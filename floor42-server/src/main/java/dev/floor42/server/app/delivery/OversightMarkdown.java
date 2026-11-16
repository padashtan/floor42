package dev.floor42.server.app.delivery;

import dev.floor42.server.app.delivery.OversightReport.EventRef;
import dev.floor42.server.app.delivery.OversightReport.Line;
import dev.floor42.server.app.delivery.OversightReport.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The oversight report as a Markdown file (ADR-0075). Fixed English words and the report's own codes: the server renders
 * no translation (ADR-0021). Text written by an agent or a command is made inert: no link, image, table cell, HTML or
 * code span can be made of it, and a bare address does not become a link.
 */
final class OversightMarkdown {

    private OversightMarkdown() {}

    /** A Java twin of the page's {@code inert} (ui/proofDossier.ts): the same characters, the same entities. */
    static String inert(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replaceAll("[\\\\`*_\\[\\]()|!#~]", "\\\\$0")
                .replace("://", ":\\/\\/");
    }

    static String render(OversightReport r) {
        List<String> out = new ArrayList<>();
        out.add("# " + inert("Human oversight report of " + r.mission().id()));
        out.add("");
        out.add(r.statement());
        out.add("");
        out.add(inert(r.mission().origin() + " mission, " + r.mission().status() + ". "
                + r.mission().events()
                + " events (" + r.mission().real() + " real, " + r.mission().simulated() + " simulated). Floor42 "
                + r.version() + ", generated " + r.generatedAt() + "."));
        out.add("");
        section(out, "Who supervised", r.supervision());
        section(out, "What the agents could do", r.capabilities());
        section(out, "What the agents did", r.actions());
        section(out, "What humans overrode", r.overrides());
        out.add("## Masked or left out");
        out.add("");
        r.masked().forEach(m -> out.add("- " + m.kind() + ": " + m.count()));
        r.limits().forEach(l -> out.add("- limit: " + l));
        out.add("");
        return String.join("\n", out);
    }

    private static void section(List<String> out, String title, Section s) {
        out.add("## " + title + " (" + s.count() + ")");
        out.add("");
        if (s.items().isEmpty()) out.add("Nothing recorded.");
        for (Line l : s.items()) out.add("- " + line(l));
        if (s.count() > s.items().size()) out.add("");
        if (s.count() > s.items().size()) out.add(s.items().size() + " of " + s.count() + " shown");
        out.add("");
    }

    private static String line(Line l) {
        List<String> parts = new ArrayList<>();
        parts.add(l.kind() + " (" + inert(l.actor()) + ", " + l.at() + ")");
        if (l.scope() != null) parts.add("scope: " + inert(l.scope()));
        if (l.code() != null) parts.add("code: " + inert(l.code()));
        if (l.basis() != null) parts.add("basis: " + inert(l.basis()));
        String head = String.join(", ", parts);
        String text = l.text() == null ? "" : ": " + inert(l.text());
        String events = l.events().stream().map(OversightMarkdown::ref).collect(Collectors.joining(", "));
        return head + text + " [" + l.origin() + "; " + events + "]";
    }

    private static String ref(EventRef e) {
        return (e.seq() == null ? "company " : "#" + e.seq() + " ") + e.type();
    }
}
