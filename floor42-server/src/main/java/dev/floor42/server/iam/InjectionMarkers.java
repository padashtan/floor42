package dev.floor42.server.iam;

import java.util.regex.Pattern;

/**
 * Phrases that an answer from an external resource has no honest reason to carry: a signal for the session risk, never
 * a block and never a rewrite of the body. shortcut: the scan reads only the capped body the broker returns, upgrade
 * if bodies get larger caps.
 */
public final class InjectionMarkers {

    private static final Pattern MARKERS = Pattern.compile(
            "ignore (all )?(previous|prior|above) instructions|disregard (the|all|your) (previous|prior|above)"
                    + "|you are now|system prompt|<\\|im_start\\|>|\\[INST]|BEGIN-UNTRUSTED-|END-UNTRUSTED-"
                    + "|call_resource|ask_director",
            Pattern.CASE_INSENSITIVE);

    private InjectionMarkers() {}

    /** True when the body holds a marker; the body itself is never kept nor logged. */
    public static boolean found(String body) {
        return body != null && MARKERS.matcher(body).find();
    }
}
