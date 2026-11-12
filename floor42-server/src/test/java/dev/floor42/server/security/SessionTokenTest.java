package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The director token: random, memory only unless the file opt-in is on, replaced at once by a rotation. */
class SessionTokenTest {

    @TempDir
    Path dir;

    private SessionToken token(boolean writeFile) {
        SessionToken t = new SessionToken();
        t.dataDir = dir.resolve("data");
        t.writeFile = writeFile;
        t.generate();
        return t;
    }

    @Test
    void theTokenIsLongRandomAndUrlSafe() {
        String v = token(false).value();
        assertThat(v).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(token(false).value()).isNotEqualTo(v);
    }

    @Test
    void onlyTheExactTokenMatches() {
        SessionToken t = token(false);
        String v = t.value();
        assertThat(t.matches(v)).isTrue();
        assertThat(t.matches(v + "x")).isFalse();
        assertThat(t.matches(v.substring(1))).isFalse();
        assertThat(t.matches(null)).isFalse();
        assertThat(t.matches("")).isFalse();
    }

    @Test
    void rotationRevokesThePreviousTokenAtOnce() {
        SessionToken t = token(false);
        String old = t.value();
        t.rotate();
        assertThat(t.matches(old)).isFalse();
        assertThat(t.matches(t.value())).isTrue();
        assertThat(t.value()).isNotEqualTo(old);
    }

    @Test
    void noTokenAtRestByDefault() {
        SessionToken t = token(false);
        assertThat(t.writtenToFile()).isFalse();
        assertThat(t.file()).isEqualTo(dir.resolve("data").resolve("session.token"));
        assertThat(Files.exists(t.file())).isFalse();
    }

    @Test
    void aFileLeftByAnEarlierVersionIsDeletedWhenTheOptInIsOff() throws IOException {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data").resolve("session.token"), "old-secret");
        SessionToken t = token(false);
        assertThat(Files.exists(t.file())).isFalse();
    }

    @Test
    void withTheOptInTheFileHoldsTheCurrentTokenAndFollowsRotation() throws IOException {
        SessionToken t = token(true);
        assertThat(t.writtenToFile()).isTrue();
        assertThat(Files.readString(t.file())).isEqualTo(t.value());
        String old = t.value();
        t.rotate();
        assertThat(Files.readString(t.file())).isEqualTo(t.value()).isNotEqualTo(old);
    }

    @Test
    void anUnwritableFolderIsReportedNotSwallowed() throws IOException {
        Path blocker = Files.writeString(dir.resolve("blocker"), "a file, not a folder");
        SessionToken t = new SessionToken();
        t.dataDir = blocker.resolve("data");
        assertThatThrownBy(t::generate)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(t.dataDir.toString());
    }

    @Test
    void equalNeverMatchesANullSide() {
        assertThat(SessionToken.sameSecret("a", "a")).isTrue();
        assertThat(SessionToken.sameSecret(null, "a")).isFalse();
        assertThat(SessionToken.sameSecret("a", null)).isFalse();
        assertThat(SessionToken.sameSecret("a", "b")).isFalse();
    }

    @Test
    void randomHasTheRequestedLength() {
        assertThat(SessionToken.random(16)).hasSize(22);
        assertThat(SessionToken.random(32)).hasSize(43);
    }
}
