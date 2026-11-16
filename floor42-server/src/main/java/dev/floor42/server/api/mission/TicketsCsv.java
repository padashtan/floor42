package dev.floor42.server.api.mission;

import dev.floor42.server.api.mission.TicketViews.TicketView;
import dev.floor42.server.security.SecretMasker;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A mission's tickets as a CSV Jira's importer reads (RFC 4180: comma, CRLF, quotes doubled). It leaves the machine, so
 * free text goes through {@link SecretMasker}, and a cell a spreadsheet would run as a formula is neutralised.
 */
final class TicketsCsv {

    private static final String HEADER =
            "Issue ID,Summary,Description,Issue Type,Priority,Status,Labels,Created,Resolved";
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private TicketsCsv() {}

    static String of(List<TicketView> tickets, String missionId) {
        StringBuilder out = new StringBuilder(HEADER).append("\r\n");
        for (TicketView t : tickets) {
            out.append(String.join(
                            ",",
                            cell(t.id()),
                            cell(SecretMasker.maskText(t.title())),
                            cell(SecretMasker.maskText(description(t))),
                            cell("Task"),
                            cell(t.priority()),
                            cell(t.status()),
                            cell("floor42 " + missionId + (t.assignee() == null ? "" : " agent-" + t.assignee())),
                            cell(when(t.createdAt())),
                            cell(when(t.endedAt()))))
                    .append("\r\n");
        }
        return out.toString();
    }

    private static String description(TicketView t) {
        List<String> parts = new ArrayList<>();
        if (t.description() != null && !t.description().isBlank()) parts.add(t.description());
        if (t.acceptanceCriteria() != null && !t.acceptanceCriteria().isEmpty())
            parts.add("Acceptance criteria:\n"
                    + t.acceptanceCriteria().stream().map(c -> "- " + c).collect(Collectors.joining("\n")));
        if (t.dependsOn() != null && !t.dependsOn().isEmpty())
            parts.add("Depends on: " + String.join(", ", t.dependsOn()));
        if (t.deliverable() != null && !t.deliverable().isBlank()) parts.add("Deliverable: " + t.deliverable());
        return String.join("\n\n", parts);
    }

    private static String when(String iso) {
        return iso == null ? "" : WHEN.format(Instant.parse(iso));
    }

    /** One cell: a leading apostrophe when it would start a formula, quoted when it holds a comma, a quote or a break. */
    static String cell(String raw) {
        String s = raw == null ? "" : raw;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
