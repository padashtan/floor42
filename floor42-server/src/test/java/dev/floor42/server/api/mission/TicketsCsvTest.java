package dev.floor42.server.api.mission;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.api.mission.TicketViews.TicketView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Jira export: RFC 4180, masked, and no cell a spreadsheet would run as a formula. */
class TicketsCsvTest {

    private static TicketView ticket(
            String id,
            String title,
            String description,
            List<String> criteria,
            List<String> deps,
            String assignee,
            String deliverable,
            String endedAt) {
        return new TicketView(
                id,
                title,
                description,
                "HIGH",
                "DONE",
                assignee,
                deps,
                criteria,
                deliverable,
                List.of(),
                List.of(),
                List.of(),
                "2026-11-16T09:05:30Z",
                null,
                endedAt,
                "WORK",
                null,
                null,
                null,
                false);
    }

    private static TicketView simple(String title) {
        return ticket("T-1", title, "d", List.of(), List.of(), null, null, null);
    }

    /** A minimal RFC 4180 reader: the test proves the writer by reading it back. */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else cell.append(c);
        }
        return rows;
    }

    @Test
    void aCellThatStartsAFormulaIsNeutralised() {
        for (String title : List.of("=HYPERLINK(\"http://x\",\"y\")", "+1", "-1", "@x", "\tx", "\rx")) {
            List<List<String>> rows = parse(TicketsCsv.of(List.of(simple(title)), "M-1"));
            assertThat(rows).hasSize(2);
            assertThat(rows.get(1).get(1)).as("summary %s", title).isEqualTo("'" + title);
        }
        assertThat(TicketsCsv.cell("safe")).isEqualTo("safe");
    }

    @Test
    void quotesCommasAndLineBreaksAreEscaped() {
        String title = "a, \"b\"\nc";
        List<List<String>> rows = parse(TicketsCsv.of(List.of(simple(title)), "M-1"));
        assertThat(rows.get(0)).hasSize(9);
        assertThat(rows.get(1)).hasSize(9);
        assertThat(rows.get(1).get(1)).isEqualTo(title);
        assertThat(rows.get(1).get(7)).isEqualTo("2026-11-16 09:05");
        assertThat(rows.get(1).get(8)).isEmpty();
    }

    @Test
    void criteriaAndDependenciesGoIntoTheDescription() {
        TicketView t = ticket(
                "T-3",
                "t",
                "Do it",
                List.of("one", "two"),
                List.of("T-1", "T-2"),
                null,
                "a.md",
                "2026-11-16T10:00:00Z");
        List<String> row = parse(TicketsCsv.of(List.of(t), "M-1")).get(1);
        assertThat(row.get(2))
                .isEqualTo("Do it\n\nAcceptance criteria:\n- one\n- two\n\nDepends on: T-1, T-2\n\nDeliverable: a.md");
        assertThat(row.get(8)).isEqualTo("2026-11-16 10:00");
    }

    @Test
    void anAgentIsALabelNotAnAssignee() {
        List<List<String>> rows =
                parse(TicketsCsv.of(List.of(ticket("T-1", "t", "d", List.of(), List.of(), "ada", null, null)), "M-7"));
        assertThat(rows.get(0)).doesNotContain("Assignee");
        assertThat(rows.get(1).get(6)).isEqualTo("floor42 M-7 agent-ada");
        assertThat(rows.get(1).get(3)).isEqualTo("Task");
    }

    @Test
    void aSecretInADescriptionIsMasked() {
        String csv = TicketsCsv.of(
                List.of(ticket(
                        "T-1",
                        "t",
                        "use token=abc123 and sk-ant-api03-abcdefghijklmnop",
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null)),
                "M-1");
        assertThat(csv).doesNotContain("abc123").doesNotContain("sk-ant-api03").contains("[masked]");
    }
}
