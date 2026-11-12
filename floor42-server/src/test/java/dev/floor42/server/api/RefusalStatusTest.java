package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.DomainException;
import dev.floor42.domain.Refusal;
import dev.floor42.server.app.RefusedException;
import org.junit.jupiter.api.Test;

class RefusalStatusTest {

    @Test
    void everyRefusalKeepsTheStatusItsCodeAlwaysHad() {
        for (Refusal r : Refusal.values()) {
            assertThat(ErrorMappers.status(new DomainException(r, "refused")))
                    .as(r.code())
                    .isEqualTo(ErrorMappers.byName(r.code()));
        }
    }

    @Test
    void aMissingFieldIsAnInvalidRequestAndAServerCodeKeepsItsStatus() {
        assertThat(ErrorMappers.status(new DomainException("MISSING_TITLE", "title is required")))
                .isEqualTo(RefusedException.BAD_REQUEST);
        assertThat(ErrorMappers.status(new DomainException("FILE_TOO_LARGE", "too large")))
                .isEqualTo(RefusedException.TOO_LARGE);
        assertThat(ErrorMappers.status(new DomainException(Refusal.UNKNOWN_DEPENDENCY, "x")))
                .isEqualTo(RefusedException.BAD_REQUEST);
        assertThat(ErrorMappers.status(new DomainException(Refusal.UNKNOWN_TICKET, "x")))
                .isEqualTo(RefusedException.NOT_FOUND);
        assertThat(ErrorMappers.status(new DomainException(Refusal.INVALID_TRANSITION, "x")))
                .isEqualTo(RefusedException.CONFLICT);
    }
}
