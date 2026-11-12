package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.workflow.WorkflowFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The codes an import can answer, written for the web's own test (floor42-web/src/api/import-codes.json): the screen has
 * a sentence for each, in both languages, and that test fails for a code without one. Refresh the file with
 * -Dfloor42.openapi.write=true, like the other contract files.
 */
class ImportCodesContractTest {

    static final Path FILE = Path.of("..", "floor42-web", "src", "api", "import-codes.json");

    @Test
    void theWebKnowsEveryCodeAnImportCanAnswer() throws Exception {
        String codes = Arrays.stream(WorkflowFile.Code.values())
                        .map(c -> "  \"" + c.name() + "\"")
                        .reduce((a, b) -> a + ",\n" + b)
                        .orElse("")
                + "\n";
        String text = "[\n" + codes + "]\n";
        if (Boolean.getBoolean("floor42.openapi.write")) Files.writeString(FILE, text);
        assertThat(Files.readString(FILE).replace("\r\n", "\n"))
                .as("The codes changed: refresh %s (see this test's documentation)", FILE)
                .isEqualTo(text);
    }
}
