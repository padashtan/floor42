package dev.floor42.domain;

import java.util.Objects;

/**
 * One committee of a mission: the mission and the committee's number in it, from 1 in the order they were convened
 * (ADR-0046). Every committee query and command addresses one committee this way, never "the" committee of a mission.
 */
public record CommitteeRef(MissionId mission, int number) {

    public CommitteeRef {
        Objects.requireNonNull(mission, "mission");
        if (number < 1) throw new IllegalArgumentException("A committee's number starts at 1: " + number);
    }

    @Override
    public String toString() {
        return mission + " committee " + number;
    }
}
