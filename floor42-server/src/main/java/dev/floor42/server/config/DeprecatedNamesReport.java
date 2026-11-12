package dev.floor42.server.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Map;
import java.util.TreeMap;
import org.jboss.logging.Logger;

/** Says once, at startup, which former Office configuration names were used, and their Floor42 names. */
@ApplicationScoped
public class DeprecatedNamesReport {

    private static final Logger LOG = Logger.getLogger(DeprecatedNamesReport.class);

    void report(@Observes StartupEvent event) {
        Map<String, String> used = new TreeMap<>(LegacyNames.USED);
        used.forEach((old, now) -> LOG.warnf(
                "Configuration name %s (or its environment variable %s) is deprecated since Office became Floor42:"
                        + " use %s (%s). It is still read.",
                old, env(old), now, env(now)));
    }

    static String env(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(java.util.Locale.ROOT);
    }
}
