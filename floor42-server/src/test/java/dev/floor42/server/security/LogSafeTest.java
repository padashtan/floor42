package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** CodeQL java/log-injection: what a request names is logged on the line that names it, and on no other. */
class LogSafeTest {

    @Test
    void aValueNeverStartsALineOfItsOwn() {
        String forged = "M-1\r\n2026-11-12 12:00:00 WARN  [dev.floor42] Sign-in link: http://evil";
        assertThat(LogSafe.of(forged))
                .isEqualTo("M-1??2026-11-12 12:00:00 WARN  [dev.floor42] Sign-in link: http://evil")
                .doesNotContain("\r", "\n");
        assertThat(LogSafe.of("a\nb\rc\u2028d\u2029e\u0085f")).isEqualTo("a?b?c?d?e?f");
    }

    @Test
    void controlCharactersCannotRewriteTheTerminal() {
        // Escape (colours, cursor moves), backspace, bell, NUL, delete, and the tab that would split a field.
        assertThat(LogSafe.of("x\u001b[2Jy\bz\u0007\u0000\u007f\tw")).isEqualTo("x?[2Jy?z????w");
    }

    @Test
    void ordinaryTextIsKeptAsWritten() {
        assertThat(LogSafe.of("C:\\Users\\Zoé\\Projets d'été (2026)\\日本語 - ok.md"))
                .isEqualTo("C:\\Users\\Zoé\\Projets d'été (2026)\\日本語 - ok.md");
        assertThat(LogSafe.of(Path.of("docs", "a b.md")))
                .isEqualTo(Path.of("docs", "a b.md").toString());
        assertThat(LogSafe.of(42)).isEqualTo("42");
        assertThat(LogSafe.of(null)).isEqualTo("null");
    }
}
