package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.workflow.StepText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The characters a step text may hold (ADR-0052), written for the web's editor (floor42-web/src/api/step-text-table.json)
 * as ranges of code points from the daemon's own rule, at the JDK's Unicode version: the editor reads this table instead
 * of its browser's Unicode, so both agree by construction. Refresh the file with -Dfloor42.openapi.write=true, like the
 * other contract files.
 */
class StepTextTableContractTest {

    static final Path FILE = Path.of("..", "floor42-web", "src", "api", "step-text-table.json");

    /** The allowed code points as closed ranges, in order. */
    static List<int[]> ranges() {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT + 1; cp++) {
            boolean in = cp <= Character.MAX_CODE_POINT && StepText.allowed(cp);
            if (in && start < 0) start = cp;
            if (!in && start >= 0) {
                out.add(new int[] {start, cp - 1});
                start = -1;
            }
        }
        return out;
    }

    static String text() {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append(
                        "  \"about\": \"The code points a workflow step text may hold, as closed ranges, written by the daemon's")
                .append(
                        " StepTextTableContractTest from its own rule (ADR-0052). The line feed is allowed in does only.")
                .append(" Text must also equal its NFKC form.\",\n");
        b.append("  \"jdk\": ").append(Runtime.version().feature()).append(",\n");
        b.append("  \"ranges\": [\n");
        List<int[]> r = ranges();
        for (int i = 0; i < r.size(); i++) {
            b.append("    [")
                    .append(r.get(i)[0])
                    .append(", ")
                    .append(r.get(i)[1])
                    .append(']');
            b.append(i < r.size() - 1 ? ",\n" : "\n");
        }
        b.append("  ]\n}\n");
        return b.toString();
    }

    @Test
    void theWebReadsTheDaemonsTableOfAllowedCharacters() throws Exception {
        String text = text();
        if (Boolean.getBoolean("floor42.openapi.write")) Files.writeString(FILE, text);
        String written = Files.readString(FILE).replace("\r\n", "\n");
        assertThat(written)
                .as("The rule or the JDK's Unicode changed: refresh %s (see this test's documentation)", FILE)
                .isEqualTo(text);
        // Every code point agrees between the rule and the table as written.
        List<int[]> read = new ArrayList<>();
        Matcher m = Pattern.compile("\\[(\\d+), (\\d+)]").matcher(written);
        while (m.find()) read.add(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        int i = 0;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            while (i < read.size() && read.get(i)[1] < cp) i++;
            boolean inTable = i < read.size() && read.get(i)[0] <= cp;
            if (inTable != StepText.allowed(cp)) {
                assertThat(inTable).as("U+%04X", cp).isEqualTo(StepText.allowed(cp));
            }
        }
        assertThat(StepText.allowed(0x2800)).isFalse();
        assertThat(StepText.allowed(0x115F)).isFalse();
        assertThat(StepText.allowed('a')).isTrue();
    }
}
