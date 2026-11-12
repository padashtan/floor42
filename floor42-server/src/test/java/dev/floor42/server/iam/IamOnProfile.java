package dev.floor42.server.iam;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;
import java.util.Map;

/** IAM mode on, against one fake Keycloak shared by the tests that use this profile. */
public class IamOnProfile implements QuarkusTestProfile {

    public static volatile FakeKeycloak fake;

    /** Starts the fake issuer and points IAM mode at it. */
    public static class FakeIssuer implements QuarkusTestResourceLifecycleManager {
        @Override
        public Map<String, String> start() {
            fake = FakeKeycloak.start("floor42");
            fake.registerClient("floor42-desk", null);
            return Map.of("floor42.iam.issuer", fake.issuer().toString());
        }

        @Override
        public void stop() {
            fake.close();
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floor42.iam.enabled", "true");
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(FakeIssuer.class));
    }
}
