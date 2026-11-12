package dev.floor42.server.iam;

import java.util.Map;

/** IAM mode on against the fake issuer, the sandbox taken as working, and no mission orchestrator in the way. */
public class IamSessionProfile extends IamOnProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floor42.iam.enabled", "true",
                "floor42.live-missions.enabled", "false",
                "floor42.claude.session-timeout-seconds", "20",
                "floor42.claude.question-timeout-seconds", "4",
                "floor42.claude.hook-timeout-seconds", "20",
                "floor42.sandbox.assume-available", "true");
    }
}
