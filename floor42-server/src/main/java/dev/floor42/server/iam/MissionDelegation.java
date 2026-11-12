package dev.floor42.server.iam;

import dev.floor42.domain.MissionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the director delegated to one mission's agents, as metadata only: the token itself lives in the vault under
 * {@code vaultEntry}. {@code revoked} is set before Keycloak is asked, and never goes back.
 */
public record MissionDelegation(
        MissionId mission,
        String vaultEntry,
        Instant grantedAt,
        Instant expiresAt,
        List<String> resources,
        boolean revoked) {
    public MissionDelegation {
        resources = resources == null ? null : Collections.unmodifiableList(new ArrayList<>(resources));
    }
}
