package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NodeTestsMissingDependencyTest {

    @Test
    void anOutputThatCouldNotFetchDependenciesNamesItsFirstSuchLine() {
        String out = "> vitest\nnpm ERR! code EAI_AGAIN\nnpm ERR! request to https://registry.npmjs.org failed\n";
        assertThat(NodeTests.missingDependency(out)).contains("npm ERR! code EAI_AGAIN");
    }

    @Test
    void eachKnownFetchFailureIsRecognised() {
        for (String line : new String[] {
            "getaddrinfo ENOTFOUND registry.npmjs.org",
            "npm ERR! code E404",
            "[ERROR] Could not resolve dependencies for project x",
            "Could not transfer artifact org.x:y:pom:1 from/to central"
        }) {
            assertThat(NodeTests.missingDependency("a\n" + line + "\nb"))
                    .as(line)
                    .contains(line);
        }
    }

    @Test
    void anAssertionFailureIsTheDevelopersOwn() {
        assertThat(NodeTests.missingDependency("FAIL src/a.test.ts\nAssertionError: expected 1 to be 2"))
                .isEmpty();
    }
}
