package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * No failure is swallowed silently in the main sources of the domain and the server: every catch clause either throws
 * (the failure, or another with it as cause) or says, in a {@code // Handled:} comment inside it, what happens instead
 * and why that is right (logged, reported in the answer, retried, a value that stands for "unknown").
 */
class CatchGateTest {

    @Test
    void everyCatchRethrowsOrSaysHowItHandlesTheFailure() {
        List<String> silent = SourceScan.mainSources().catches().stream()
                .filter(c -> !c.rethrows() && !c.explained())
                .map(c -> c.file() + ":" + c.line())
                .toList();
        assertThat(silent)
                .as("catch blocks with neither a throw nor a // Handled: reason (SourceScan.main lists them)")
                .isEmpty();
    }

    /** The gates' scanner on a planted source: a silent catch, an explained one, a rethrow, and a 32-line body. */
    @Test
    void theScanFindsASilentCatchAndMeasuresABodyBraceToBrace(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++)
            body.append("        int v").append(i).append(" = ").append(i).append(";\n");
        String source = "package planted;\n\nclass Planted {\n    void silent() {\n        try {\n"
                + "            run();\n        } catch (RuntimeException e) {\n            // nothing\n        }\n    }\n\n"
                + "    void explained() {\n        try {\n            run();\n        } catch (RuntimeException e) {\n"
                + "            // Handled: a reason\n        }\n    }\n\n"
                + "    void rethrows() {\n        try {\n            run();\n        } catch (RuntimeException e) {\n"
                + "            throw new IllegalStateException(e);\n        }\n    }\n\n"
                + "    void run() {}\n\n    void longOne() {\n" + body + "    }\n}\n";
        java.nio.file.Files.writeString(dir.resolve("Planted.java"), source);
        SourceScan scan = SourceScan.of(List.of(dir));
        assertThat(scan.catches())
                .extracting(c -> c.line() + ":" + c.rethrows() + ":" + c.explained())
                .containsExactly("7:false:false", "15:false:true", "23:true:false");
        assertThat(scan.methods())
                .filteredOn(m -> m.name().equals("longOne"))
                .singleElement()
                .satisfies(m -> assertThat(m.lines()).isEqualTo(32));
    }

    @Test
    void theScanSeesCatchClauses() {
        // A scan that found nothing would pass the gate above for the wrong reason.
        assertThat(SourceScan.mainSources().catches()).hasSizeGreaterThan(100);
    }
}
