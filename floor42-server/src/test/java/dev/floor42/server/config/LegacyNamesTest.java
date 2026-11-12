package dev.floor42.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Office's configuration names keep working after the rename to Floor42 (1.5.0). */
class LegacyNamesTest {

    /** application.properties has ordinal 250, the environment 300, system properties 400. */
    private static SmallRyeConfig config(String profile, Map<String, String> defaults, Map<String, String> env) {
        SmallRyeConfigBuilder b = new SmallRyeConfigBuilder()
                .withInterceptors(new LegacyNames())
                .withSources(new PropertiesConfigSource(defaults, "application.properties", 250))
                .withSources(new io.smallrye.config.EnvConfigSource(env, 300));
        if (profile != null) b.withProfile(profile);
        return b.build();
    }

    @Test
    void anOldEnvironmentVariableStillOverridesTheDefault() {
        LegacyNames.USED.clear();
        SmallRyeConfig c = config(
                null,
                Map.of("floor42.data-dir", "/home/x/.floor42", "floor42.gh.command", "gh"),
                Map.of("OFFICE_DATA_DIR", "/data/old"));

        assertThat(c.getValue("floor42.data-dir", String.class)).isEqualTo("/data/old");
        assertThat(c.getValue("floor42.gh.command", String.class)).isEqualTo("gh");
        assertThat(LegacyNames.USED).containsEntry("office.data-dir", "floor42.data-dir");
        assertThat(DeprecatedNamesReport.env("office.data-dir")).isEqualTo("OFFICE_DATA_DIR");
    }

    @Test
    void theNewNameWinsOverTheOldOneFromTheSameSource() {
        SmallRyeConfig c =
                config(null, Map.of(), Map.of("OFFICE_DATA_DIR", "/data/old", "FLOOR42_DATA_DIR", "/data/new"));

        assertThat(c.getValue("floor42.data-dir", String.class)).isEqualTo("/data/new");
    }

    @Test
    void anOldNameAloneIsRead() {
        SmallRyeConfig c = config(null, Map.of("office.retro.enabled", "false"), Map.of());

        assertThat(c.getValue("floor42.retro.enabled", Boolean.class)).isFalse();
        assertThat(c.getOptionalValue("floor42.stall.minutes", Integer.class)).isEmpty();
    }

    @Test
    void profilesToo() {
        SmallRyeConfig c =
                config("test", Map.of("office.retro.enabled", "true", "%test.office.retro.enabled", "false"), Map.of());

        assertThat(c.getValue("floor42.retro.enabled", Boolean.class)).isFalse();
    }

    @Test
    void otherNamesAreLeftAlone() {
        assertThat(LegacyNames.legacy("quarkus.http.port")).isNull();
        assertThat(LegacyNames.legacy("floor42.data-dir")).isEqualTo("office.data-dir");
        assertThat(LegacyNames.legacy("%dev.floor42.data-dir")).isEqualTo("%dev.office.data-dir");
    }

    @Test
    void aHugeNameIsReadInLinearTime() {
        for (String name : new String[] {
            "%" + "a".repeat(50_000),
            "%" + "a".repeat(50_000) + ".floor42." + "x".repeat(50_000),
            "%" + "a.".repeat(25_000),
            "floor42.".repeat(6_250),
        }) {
            // The bugs this guards took tens of seconds; the gate runs under load.
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> LegacyNames.legacy(name));
        }
    }
}
