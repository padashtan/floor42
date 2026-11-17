package dev.floor42.server.iam;

import dev.floor42.domain.MissionId;
import java.util.List;

/**
 * Told when the resources a mission's delegation names start or stop counting: in IAM mode they are OpenFGA tuples
 * (ADR-0080). A sink that fails never undoes a delegation or its revocation: the local checks come first and the start
 * reconciler repairs what a failed call left.
 */
public interface DelegationSink {

    /** The delegation of {@code mission} names {@code resources}. */
    void granted(MissionId mission, List<String> resources);

    /** The delegation of {@code mission} no longer counts for {@code resources}; {@code reason} is DELEGATION or QUARANTINE. */
    void ended(MissionId mission, List<String> resources, String reason);
}
