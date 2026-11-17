package dev.floor42.server.iam.fga;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

/**
 * The OpenFGA of every Quarkus test: replaces the live client, so a grant, a delegation or a tick is a tuple in memory.
 * A test that cares injects it, and {@link #reset} in its own {@code @AfterEach}.
 */
@Singleton
@Alternative
@Priority(1)
public class MockFga extends EvaluatingFga {}
