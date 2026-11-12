package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The end-of-mission retrospective (ADR-0020): those who worked speak once each, the chair concludes, and only the
 * director makes a lesson binding.
 */
@ExtendWith(GoldenFixtureExtension.class)
class RetroTest {

    final Fixture f = new Fixture();

    /** A mission Chris worked on, then the director closed (a cancelled one holds no retrospective). */
    MissionId ended() {
        MissionId m = f.runningMission();
        f.record(f.company.sessions().start(f.system(), "s-1", f.firstTicket(m), CHRIS, "claude-sonnet-5-5", "2.1"));
        f.record(f.company.sessions().end(f.system(), "s-1", false, "completed", 3, 10L, 10L, 0.02));
        f.record(f.company.tickets().cancel(f.director(), f.firstTicket(m), "not needed"));
        f.record(f.company.missions().complete(f.director(), m, "enough"));
        return m;
    }

    @Test
    void onlyThoseWhoWorkedOnAnEndedMissionTakePart() {
        MissionId running = f.runningMission();
        assertThatThrownBy(() -> f.company.retros().open(f.system(), running, CHRIS, List.of(CHRIS), "facts"))
                .hasMessageContaining("has not ended");

        MissionId m = ended();
        assertThatThrownBy(() -> f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS, DARIUS), "facts"))
                .hasMessageContaining("did not work on");
        assertThatThrownBy(() -> f.company.retros().open(f.director(), m, CHRIS, List.of(CHRIS), "facts"))
                .isInstanceOf(DomainException.class);

        f.record(f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS), "facts"));
        // Work under way elsewhere comes first; once it is over, Chris sits at the table.
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.WORKING);
        f.record(f.company.missions().cancel(f.director(), running, "done"));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.IN_RETRO);
        assertThatThrownBy(() -> f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS), "facts"))
                .hasMessageContaining("already");
    }

    @Test
    void aCancelledMissionHoldsNoRetrospective() {
        MissionId m = f.runningMission();
        f.record(f.company.missions().cancel(f.director(), m, "enough"));
        assertThatThrownBy(() -> f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS), "facts"))
                .extracting("code")
                .isEqualTo("MISSION_NOT_ACTIVE");
    }

    @Test
    void aParticipantSpeaksOnceThenTheChairConcludesWithLessons() {
        MissionId m = ended();
        f.record(f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS), "Tickets: 1 cancelled"));
        Retro r = f.company.retros().find(m).orElseThrow();
        // The retrospective is the one session allowed on an ended mission, for a participant only.
        f.record(f.company.sessions().start(f.system(), "s-2", r.ticket(), CHRIS, "claude-haiku-5-5", "2.1"));
        assertThatThrownBy(() -> f.company.sessions().start(f.system(), "s-3", r.ticket(), DARIUS, "m", "2.1"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.retros().conclude(f.agent(CHRIS), m, List.of(), "x", null, List.of()))
                .hasMessageContaining("not spoken");

        f.record(f.company
                .retros()
                .contribute(f.agent(CHRIS), m, List.of("Small ticket"), List.of("Cancelled before review"), "My say"));
        assertThatThrownBy(() -> f.company.retros().contribute(f.agent(CHRIS), m, List.of(), List.of(), "Again"))
                .hasMessageContaining("not expected");
        assertThatThrownBy(() -> f.company
                        .retros()
                        .conclude(
                                f.agent(CHRIS),
                                m,
                                List.of(),
                                "x",
                                null,
                                java.util.Collections.nCopies(
                                        RetrosFacade.MAX_LESSONS_PER_RETRO + 1, new Lesson.Proposal(null, "too many"))))
                .hasMessageContaining("At most");

        f.record(f.company
                .retros()
                .conclude(
                        f.agent(CHRIS),
                        m,
                        List.of("Scope was too wide"),
                        "Conclusion",
                        null,
                        List.of(
                                new Lesson.Proposal(WorkRole.DEVELOPER, "Split tickets above a day"),
                                new Lesson.Proposal(null, "Say what was not verified"))));

        Retro done = Company.replay(f.journal).retros().find(m).orElseThrow();
        assertThat(done.concluded()).isTrue();
        assertThat(done.contributions()).singleElement().satisfies(c -> {
            assertThat(c.wentWell()).containsExactly("Small ticket");
            assertThat(c.wentBadly()).containsExactly("Cancelled before review");
        });
        assertThat(f.company.tickets().find(done.ticket()).orElseThrow().status())
                .isEqualTo(TicketStatus.DONE);
        assertThat(f.company.queries().statusOf(CHRIS)).isNotEqualTo(AgentStatus.IN_RETRO);
        assertThat(f.company.retros().lessons()).extracting(Lesson::status).containsOnly(Lesson.Status.PROPOSED);
        // Proposed is not binding: no brief carries it yet.
        assertThat(f.company.retros().lessonsFor(WorkRole.DEVELOPER)).isEmpty();
    }

    @Test
    void onlyTheDirectorMakesALessonBinding() {
        MissionId m = ended();
        f.record(f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS), "facts"));
        f.record(f.company.retros().contribute(f.agent(CHRIS), m, List.of(), List.of(), "say"));
        f.record(f.company
                .retros()
                .conclude(
                        f.agent(CHRIS),
                        m,
                        List.of(),
                        "done",
                        null,
                        List.of(
                                new Lesson.Proposal(WorkRole.REVIEWER, "Run the tests yourself"),
                                new Lesson.Proposal(null, "Keep reports short"))));

        assertThatThrownBy(() -> f.company.retros().acceptLesson(f.agent(CHRIS), "L-1", null))
                .isInstanceOf(DomainException.class);
        f.record(f.company.retros().acceptLesson(f.director(), "L-1", "Run the tests yourself, every time"));
        f.record(f.company.retros().rejectLesson(f.director(), "L-2", "too vague"));

        Company replayed = Company.replay(f.journal);
        assertThat(replayed.retros().lessonsFor(WorkRole.REVIEWER))
                .extracting(Lesson::text)
                .containsExactly("Run the tests yourself, every time");
        assertThat(replayed.retros().lessonsFor(WorkRole.DEVELOPER)).isEmpty();

        f.record(f.company.retros().retireLesson(f.director(), "L-1", "no longer needed"));
        assertThat(f.company.retros().lessonsFor(WorkRole.REVIEWER)).isEmpty();
        assertThatThrownBy(() -> f.company.retros().retireLesson(f.director(), "L-2", "x"))
                .hasMessageContaining("REJECTED");
    }

    @Test
    void officeClosesARetroTheChairCouldNotConclude() {
        MissionId m = ended();
        f.record(f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS), "facts"));
        f.record(f.company.retros().skip(f.system(), m, CHRIS, "the session failed"));
        assertThatThrownBy(() -> f.company
                        .retros()
                        .conclude(f.system(), m, List.of(), null, "x", List.of(new Lesson.Proposal(null, "invented"))))
                .hasMessageContaining("no lesson");
        f.record(f.company.retros().conclude(f.system(), m, List.of(), null, "nobody could speak", List.of()));

        Retro r = f.company.retros().find(m).orElseThrow();
        assertThat(r.reason()).isEqualTo("nobody could speak");
        assertThat(r.contributions())
                .singleElement()
                .satisfies(c -> assertThat(c.skipped()).isEqualTo("the session failed"));
        assertThat(f.company.retros().lessons()).isEmpty();
    }

    @Test
    void theDirectorClosesARetrospectiveThatWaitsForTheUsageWindow() {
        MissionId m = ended();
        f.record(f.company.retros().open(f.system(), m, CHRIS, List.of(CHRIS), "facts"));
        assertThatThrownBy(() -> f.company.retros().closeNow(f.agent(CHRIS), m)).isInstanceOf(DomainException.class);

        List<dev.floor42.domain.event.EventEnvelope> events =
                f.record(f.company.retros().closeNow(f.director(), m));

        assertThat(events)
                .extracting(dev.floor42.domain.event.EventEnvelope::type)
                .containsExactly("RetroSkipped", "RetroConcluded");
        Retro r = Company.replay(f.journal).retros().find(m).orElseThrow();
        assertThat(r.concluded()).isTrue();
        assertThat(r.reason()).contains("closed by the director");
        assertThatThrownBy(() -> f.company.retros().closeNow(f.director(), m)).isInstanceOf(DomainException.class);
    }
}
