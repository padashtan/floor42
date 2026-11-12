package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hole of 1.7.0 in Docker: the working directory {@code /home/office} was writable by the agents' uid, and Quarkus
 * reads {@code .env} and {@code config/application.properties} from it. A planted {@code
 * FLOOR42_PROCESS_NON_DUMPABLE=false}, then {@code kill -TERM 1}, made the restarted daemon dumpable again (checked in
 * a built container, 2026-11-01). Now such a file refuses the start, and so does any change to the journal's
 * connection.
 */
class ConfigGuardTest {

    @TempDir
    Path cwd;

    static ConfigSource source(String name) {
        return new ConfigSource() {
            @Override
            public Set<String> getPropertyNames() {
                return Set.of();
            }

            @Override
            public String getValue(String propertyName) {
                return null;
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    @Test
    void aDotEnvOrAConfigFolderInTheWorkingDirectoryRefusesTheStart() {
        String dotEnv = "EnvConfigSource[source=" + cwd.resolve(".env").toUri() + "]";
        String config = "PropertiesConfigSource[source="
                + cwd.resolve("config/application.properties").toUri() + "]";
        List<String> problems = ConfigGuard.workingDirectorySources(
                List.of(source(dotEnv), source(config), source("EnvConfigSource"), source("SysPropConfigSource")), cwd);

        assertThat(problems).hasSize(2);
        assertThat(problems.getFirst()).contains(".env").contains("working directory");
    }

    @Test
    void theDaemonsOwnFilesAndTheEnvironmentAreNotRefused() {
        String inJar = "PropertiesConfigSource[source=jar:file:/opt/floor42/app/floor42.jar!/application.properties]";
        // Dev mode reads target/classes/application.properties, inside the working directory but not in config/.
        String devMode = "PropertiesConfigSource[source="
                + cwd.resolve("target/classes/application.properties").toUri() + "]";
        assertThat(ConfigGuard.workingDirectorySources(
                        List.of(source(inJar), source(devMode), source("EnvConfigSource")), cwd))
                .isEmpty();
    }

    /** QUARKUS_CONFIG_LOCATIONS pointing into the home volume: allowed (the director's environment), but said. */
    @Test
    void aConfigurationFileOutsideTheDaemonsOwnFilesIsReported() {
        Path own = cwd.resolve("opt/floor42");
        String inVolume = "PropertiesConfigSource[source="
                + cwd.resolve("home/office/.floor42/x.properties").toUri() + "]";
        String inBuild = "PropertiesConfigSource[source="
                + own.resolve("classes/application.properties").toUri() + "]";
        assertThat(ConfigGuard.foreignFiles(List.of(source(inVolume), source(inBuild), source("EnvConfigSource")), own))
                .singleElement()
                .asString()
                .endsWith("x.properties");
        assertThat(ConfigGuard.ownRoot()).as("the test's own build").isNotNull();
    }

    static SmallRyeConfig config(Map<String, String> extra) {
        Map<String, String> p = new HashMap<>();
        p.put("floor42.data-dir", "/home/office/.floor42");
        p.put("quarkus.datasource.jdbc.url", "jdbc:sqlite:${floor42.data-dir}/floor42.db?transaction_mode=IMMEDIATE");
        p.putAll(extra);
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(p, "test", 100))
                .build();
    }

    @Test
    void theJournalsConnectionIsTheOneTheDataFolderGives() {
        assertThat(ConfigGuard.journalConnection(config(Map.of()))).isEmpty();
        assertThat(ConfigGuard.journalConnection(config(Map.of(
                        "quarkus.datasource.jdbc.url",
                        "jdbc:sqlite:/home/office/.floor42/floor42.db?enable_load_extension=true"))))
                .isNotEmpty()
                .anyMatch(p -> p.contains("must be jdbc:sqlite:/home/office/.floor42/floor42.db"));
        assertThat(ConfigGuard.journalConnection(config(
                        Map.of("quarkus.datasource.jdbc.additional-jdbc-properties.enable_load_extension", "true"))))
                .anyMatch(p -> p.contains("changes how the journal's connection is opened"));
        assertThat(ConfigGuard.journalConnection(
                        config(Map.of("QUARKUS_DATASOURCE_JDBC_ADDITIONAL_JDBC_PROPERTIES_FOO", "bar"))))
                .isNotEmpty();
    }

    @Test
    void theTestFrameworksStartupOverrideFilesAreNotReported() {
        String override = "PropertiesConfigSource[source="
                + cwd.resolve("tmp/startup-override-1/application.properties").toUri() + "]";
        String other = "PropertiesConfigSource[source="
                + cwd.resolve("tmp/other-1/application.properties").toUri() + "]";
        assertThat(ConfigGuard.foreignFiles(List.of(source(override), source(other)), cwd.resolve("opt")))
                .singleElement()
                .asString()
                .contains("other-1");
    }

    @Test
    void aValueNamingLoadExtensionUnderTheDatasourceIsRefusedWhateverItsKey() {
        assertThat(ConfigGuard.journalConnection(config(Map.of("quarkus.datasource.jdbc.extra", "x;load_extension"))))
                .singleElement()
                .asString()
                .contains("quarkus.datasource.jdbc.extra");
        assertThat(ConfigGuard.journalConnection(config(Map.of("quarkus.datasource.jdbc.extra", "plain"))))
                .isEmpty();
    }

    private static ConfigGuard guard(SmallRyeConfig config) {
        ConfigGuard guard = new ConfigGuard();
        guard.config = config;
        return guard;
    }

    @Test
    void verifyPassesOnACleanConfigurationAndReportsForeignFiles() {
        Path elsewhere = cwd.resolve("elsewhere/x.properties");
        ConfigSource foreign = source("PropertiesConfigSource[source=" + elsewhere.toUri() + "]");
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(
                        Map.of(
                                "floor42.data-dir",
                                "/d",
                                "quarkus.datasource.jdbc.url",
                                "jdbc:sqlite:/d" + ConfigGuard.JOURNAL_URL_SUFFIX),
                        "test",
                        100))
                .withSources(foreign)
                .build();
        ConfigGuard guard = guard(config);

        assertThat(guard.foreignConfigFiles()).isEmpty();
        guard.verify();
        guard.atStart(null);

        assertThat(guard.foreignConfigFiles()).singleElement().asString().endsWith("x.properties");
    }

    @Test
    void verifyRefusesTheStartOnAForeignJournalUrlAndKeepsRefusing() {
        ConfigGuard guard = guard(config(Map.of("quarkus.datasource.jdbc.url", "jdbc:sqlite:/elsewhere/x.db")));

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Floor42 refuses to start: ")
                .hasMessageContaining("jdbc:sqlite:/elsewhere/x.db");
        assertThatThrownBy(() -> guard.atStart(null)).isInstanceOf(IllegalStateException.class);
    }
}
