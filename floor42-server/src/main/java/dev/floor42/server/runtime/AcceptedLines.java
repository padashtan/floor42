package dev.floor42.server.runtime;

import java.util.Set;

/**
 * Kinds of stream line Floor42 did not know and the director accepted: Floor42 records them (secrets masked) instead
 * of warning; implemented by the settings.
 */
public interface AcceptedLines {

    Set<String> acceptedStreamLines();
}
