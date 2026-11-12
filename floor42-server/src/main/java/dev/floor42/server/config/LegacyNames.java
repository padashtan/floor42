package dev.floor42.server.config;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Office was renamed Floor42 in 1.5.0: every {@code floor42.*} configuration name also accepts its former
 * {@code office.*} spelling (and so {@code FLOOR42_*} environment variables accept {@code OFFICE_*}), profiles
 * included. When both are set, the source with the higher ordinal wins as usual (a system property over an environment
 * variable over application.properties), the new name on a tie: an {@code OFFICE_DATA_DIR} left in the environment
 * still overrides the default data folder written in application.properties. Each old name that was used is reported
 * once at startup ({@link DeprecatedNamesReport}).
 */
@Priority(Priorities.LIBRARY + 600)
public class LegacyNames implements ConfigSourceInterceptor {

    private static final Pattern NEW = Pattern.compile("^(%[^.]+\\.)?floor42\\.(.+)$");
    private static final String OLD_PREFIX = "office.";

    /** Old name to new name, for each old name that gave a value. */
    static final Map<String, String> USED = new ConcurrentHashMap<>();

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue current = context.proceed(name);
        String legacy = legacy(name);
        if (legacy == null) return current;
        ConfigValue old = context.proceed(legacy);
        if (old == null || old.getValue() == null) return current;
        if (current != null
                && current.getValue() != null
                && current.getConfigSourceOrdinal() >= old.getConfigSourceOrdinal()) {
            return current;
        }
        USED.putIfAbsent(legacy, name);
        return old.withName(name);
    }

    /** The former spelling of a Floor42 name, or null for any other name. */
    static String legacy(String name) {
        Matcher m = NEW.matcher(name);
        if (!m.matches()) return null;
        return (m.group(1) == null ? "" : m.group(1)) + OLD_PREFIX + m.group(2);
    }
}
