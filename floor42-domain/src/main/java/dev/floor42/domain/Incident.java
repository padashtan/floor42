package dev.floor42.domain;

import java.time.Instant;

public record Incident(
        IncidentId id,
        MissionId mission,
        Criticality criticality,
        String description,
        ActionId action,
        Instant raisedAt) {}
