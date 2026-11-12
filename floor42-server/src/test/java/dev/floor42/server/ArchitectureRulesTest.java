package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import dev.floor42.server.fixture.StartsItsOwnThread;
import org.junit.jupiter.api.Test;

/** The architecture rules refuse what they claim to: each one fails on a violation planted in a fixture class. */
class ArchitectureRulesTest {

    @Test
    void threadsStartedOutsideTheSchedulerAreRefused() {
        JavaClasses rogue = new ClassFileImporter().importClasses(StartsItsOwnThread.class);
        EvaluationResult result = ArchitectureTest.threadsStartOnlyInTheScheduler.evaluate(rogue);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("Thread.ofVirtual()"))
                .anyMatch(d -> d.contains("Thread.<init>"))
                .anyMatch(d -> d.contains("Executors.newSingleThreadExecutor()"));
    }
}
