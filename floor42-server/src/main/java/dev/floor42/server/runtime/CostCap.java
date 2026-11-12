package dev.floor42.server.runtime;

import dev.floor42.domain.MissionId;

/** The most one live session may spend, in USD as Claude Code estimates it (ADR-0025); implemented by the settings. */
public interface CostCap {

    /** @param mission null for a session outside any mission */
    double sessionCapUsd(MissionId mission);
}
