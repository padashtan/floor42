package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AuthorizationTuple;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.PreferenceEvent.AuthorizationChanged;
import dev.floor42.server.iam.OfficeFacts.AuthorizationChange;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The real journal path of an {@code AuthorizationChanged} row: who wrote it, and the tuples it leaves standing. */
@QuarkusTest
@TestProfile(IamOnProfile.class)
class AuthorizationJournalTest {

    @Inject
    OfficeFacts facts;

    @Inject
    Journal journal;

    private static final AuthorizationTuple GRANT =
            new AuthorizationTuple("agent:journal-ada", "granted", "resource:journal-demo");
    private static final AuthorizationTuple DELEGATED =
            new AuthorizationTuple("mission:M-J1", "delegated", "resource:journal-demo");

    private List<EventEnvelope> rows(String object) {
        return journal.readAll().stream()
                .filter(e -> e.event() instanceof AuthorizationChanged a
                        && (a.writes().stream().anyMatch(t -> t.object().equals(object))
                                || a.deletes().stream().anyMatch(t -> t.object().equals(object))))
                .toList();
    }

    @Test
    void aGrantIsTheDirectorsAndADelegationIsFloor42sForTheDirector() {
        facts.authorizationChanged(
                new AuthorizationChange(List.of(GRANT), List.of(), "01HMODEL", "GRANT", false, false));
        facts.authorizationChanged(
                new AuthorizationChange(List.of(DELEGATED), List.of(), "01HMODEL", "DELEGATION", false, false));

        List<EventEnvelope> rows = rows("resource:journal-demo");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).actor()).isEqualTo(Actor.director());
        assertThat(rows.get(1).actor().kind()).isEqualTo(Actor.Kind.SYSTEM);
        assertThat(rows).allSatisfy(e -> assertThat(e.origin().name()).isEqualTo("REAL"));
        assertThat(facts.journaledAuthorization()).contains(GRANT, DELEGATED);

        facts.authorizationChanged(
                new AuthorizationChange(List.of(), List.of(GRANT), "01HMODEL", "GRANT", false, false));
        assertThat(facts.journaledAuthorization()).contains(DELEGATED).doesNotContain(GRANT);
    }

    @Test
    void aRowWithNothingInItIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> facts.authorizationChanged(
                        new AuthorizationChange(List.of(), List.of(), "01HMODEL", "GRANT", false, false)))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> facts.authorizationChanged(
                        new AuthorizationChange(List.of(GRANT), List.of(), "01HMODEL", "NOPE", false, false)))
                .isInstanceOf(RuntimeException.class);
    }
}
