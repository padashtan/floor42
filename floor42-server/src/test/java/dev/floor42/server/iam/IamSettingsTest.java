package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IamSettingsTest {

    @TempDir
    Path dataDir;

    private IamSettings read(Optional<Boolean> on, Optional<Integer> hours) {
        return new IamSettings(dataDir, on, Optional.empty(), hours, new ObjectMapper());
    }

    @Test
    void offByDefaultAndCappedAt8Hours() throws IOException {
        var none = read(Optional.empty(), Optional.empty());
        assertThat(none.enabled()).isFalse();
        assertThat(none.maxTokenHours()).isEqualTo(8);
        assertThat(read(Optional.of(true), Optional.of(12)).maxTokenHours()).isEqualTo(8);
        assertThat(read(Optional.of(true), Optional.of(0)).maxTokenHours()).isEqualTo(1);

        Files.createDirectories(dataDir.resolve("iam"));
        Files.writeString(
                dataDir.resolve("iam/settings.json"),
                "{\"enabled\":true,\"issuer\":\"http://kc.test/realms/x\",\"maxTokenHours\":12}");
        var file = read(Optional.empty(), Optional.empty());
        assertThat(file.enabled()).isTrue();
        assertThat(file.maxTokenHours()).isEqualTo(8);
        assertThat(file.issuer()).hasToString("http://kc.test/realms/x");
    }

    @Test
    void savingNeverChangesTheRunningModeAndAsksForARestart() {
        var s = read(Optional.empty(), Optional.empty());
        assertThat(s.restartNeeded()).isFalse();
        s.save(new IamSettings.Settings(true, "http://kc.test/realms/x", 30));
        assertThat(s.enabled()).isFalse();
        assertThat(s.restartNeeded()).isTrue();
        assertThat(s.saved().maxTokenHours()).isEqualTo(8);
        var next = read(Optional.empty(), Optional.empty());
        assertThat(next.enabled()).isTrue();
        assertThat(next.restartNeeded()).isFalse();
    }

    @Test
    void aDamagedFileNeverSwitchesIamOn() throws IOException {
        Files.createDirectories(dataDir.resolve("iam"));
        Files.writeString(dataDir.resolve("iam/settings.json"), "{not json");
        assertThat(read(Optional.empty(), Optional.empty()).enabled()).isFalse();
    }
}
