package dev.floor42.server.runtime;

import java.util.Locale;
import java.util.Map;

/**
 * The context window of a model, as Anthropic documents it (models overview, cached 2026-10-06). A model this table
 * does not know has no window: Floor42 then shows the measured tokens only, never a percentage made up against a guess.
 */
public final class ContextWindows {

    static final long ONE_MILLION = 1_000_000L;
    static final long TWO_HUNDRED_THOUSAND = 200_000L;

    /** Documented windows by model id, without a date suffix. */
    static final Map<String, Long> DOCUMENTED = Map.ofEntries(
            Map.entry("claude-fable-5-1", ONE_MILLION),
            Map.entry("claude-mythos-5-1", ONE_MILLION),
            Map.entry("claude-fable-5", ONE_MILLION),
            Map.entry("claude-opus-5-5", ONE_MILLION),
            Map.entry("claude-opus-5", ONE_MILLION),
            Map.entry("claude-sonnet-5-5", ONE_MILLION),
            Map.entry("claude-sonnet-5", ONE_MILLION),
            Map.entry("claude-haiku-5-5", ONE_MILLION),
            Map.entry("claude-opus-4-8", ONE_MILLION),
            Map.entry("claude-opus-4-7", ONE_MILLION),
            Map.entry("claude-opus-4-6", ONE_MILLION),
            Map.entry("claude-sonnet-4-6", ONE_MILLION),
            Map.entry("claude-haiku-4-5", TWO_HUNDRED_THOUSAND));

    private ContextWindows() {}

    /**
     * The window in tokens, or null when the model is unknown (an alias such as {@code sonnet} included: what it
     * resolves to depends on the Claude Code version). Claude Code's {@code [1m]} suffix, a provider prefix
     * ({@code anthropic.}, {@code us.anthropic.}) and a date suffix are ignored: the documented window decides.
     */
    public static Long of(String model) {
        if (model == null || model.isBlank()) return null;
        String id = model.strip().toLowerCase(Locale.ROOT);
        if (id.endsWith("[1m]")) id = id.substring(0, id.length() - "[1m]".length());
        int claude = id.indexOf("claude-");
        if (claude < 0) return null;
        id = id.substring(claude).replaceFirst("[-@]\\d{8}$", "").replaceFirst("-v\\d+(:\\d+)?$", "");
        return DOCUMENTED.get(id);
    }
}
