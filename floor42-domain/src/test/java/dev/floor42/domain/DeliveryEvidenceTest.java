package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.DeliveryEvent.DeliveryEvidenceRecorded;
import dev.floor42.domain.event.DeliveryEvent.PullRequestMerged;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Governed delivery (ADR-0084): the checks of an exact commit, and a merge only on green evidence of that commit. */
class DeliveryEvidenceTest {

    static final String SHA = "84b0d7cb940063d7ff3d5a95c1a0a3e941bc9da8";
    static final String OTHER = "1111111111111111111111111111111111111111";
    static final List<String> GREEN = List.of("build|pass|SUCCESS", "lint|skipping|SKIPPED");

    final Fixture f = new Fixture(Origin.REAL);

    MissionId opened() {
        MissionId id = ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .create(f.director(), "Notes", "Write notes", "C:/work/notes", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        f.record(f.company
                .deliveries()
                .recordPullRequest(f.director(), id, null, "https://x/pull/7", 7, "feature", "main", "origin", false));
        return id;
    }

    @Test
    void theConclusionFollowsTheBucketsAndACancelledRunIsNotGreen() {
        assertThat(PullRequest.conclusionOf(GREEN)).isEqualTo(PullRequest.GREEN);
        assertThat(PullRequest.conclusionOf(List.of())).isEqualTo(PullRequest.NONE);
        // Nothing passed: skipped checks prove nothing.
        assertThat(PullRequest.conclusionOf(List.of("docs|skipping|SKIPPED"))).isEqualTo(PullRequest.NONE);
        assertThat(PullRequest.conclusionOf(List.of("a|pass|SUCCESS", "b|pending|IN_PROGRESS")))
                .isEqualTo(PullRequest.PENDING);
        assertThat(PullRequest.conclusionOf(List.of("a|pass|SUCCESS", "b|cancel|CANCELLED")))
                .isEqualTo(PullRequest.FAILED);
        assertThat(PullRequest.conclusionOf(List.of("a|fail|FAILURE", "b|pending|QUEUED")))
                .isEqualTo(PullRequest.FAILED);
    }

    @Test
    void evidenceKeepsTheCommitAndTheLastOneWinsAfterAReplay() {
        MissionId m = opened();
        var first = f.record(f.company.deliveries().recordDeliveryEvidence(f.director(), m, 7, OTHER, GREEN));
        assertThat(first.getFirst().event())
                .isEqualTo(new DeliveryEvidenceRecorded(m, 7, OTHER, GREEN, PullRequest.GREEN));
        f.record(f.company.deliveries().recordDeliveryEvidence(f.director(), m, 7, SHA, List.of("build|fail|FAILURE")));

        Company replayed = Company.replay(f.journal);
        assertThat(replayed.deliveries().evidence(m, 7)).hasValueSatisfying(e -> {
            assertThat(e.commit()).isEqualTo(SHA);
            assertThat(e.conclusion()).isEqualTo(PullRequest.FAILED);
            assertThat(e.at()).isNotNull();
        });
        // The checks of the pull request list follow the latest evidence.
        assertThat(replayed.deliveries().pullRequest(m, 7).orElseThrow().checks())
                .extracting(PullRequest.Check::bucket)
                .containsExactly("fail");
    }

    @Test
    void anAgentOrOfficeItselfRecordsNothingAndTheCommitMustLookLikeOne() {
        MissionId m = opened();
        for (CommandContext who : List.of(f.agent(CHRIS), f.system())) {
            assertThatThrownBy(() -> f.company.deliveries().recordDeliveryEvidence(who, m, 7, SHA, GREEN))
                    .extracting("code")
                    .isEqualTo("FORBIDDEN");
        }
        assertThatThrownBy(() -> f.company.deliveries().recordDeliveryEvidence(f.director(), m, 7, "abc", GREEN))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
        assertThatThrownBy(() -> f.company.deliveries().recordDeliveryEvidence(f.director(), m, 8, SHA, GREEN))
                .extracting("code")
                .isEqualTo("UNKNOWN_PULL_REQUEST");
    }

    @Test
    void aMergeNeedsGreenEvidenceOfThatCommit() {
        MissionId m = opened();
        // No evidence at all: stale by definition.
        assertThatThrownBy(() -> f.company.deliveries().recordPullRequestMerged(f.director(), m, 7, SHA, "MERGE:x"))
                .extracting("code")
                .isEqualTo("EVIDENCE_STALE");
        f.record(f.company.deliveries().recordDeliveryEvidence(f.director(), m, 7, SHA, GREEN));
        // Evidence of another commit: the branch moved since.
        assertThatThrownBy(() -> f.company.deliveries().recordPullRequestMerged(f.director(), m, 7, OTHER, "MERGE:x"))
                .extracting("code")
                .isEqualTo("EVIDENCE_STALE");
        f.record(f.company
                .deliveries()
                .recordDeliveryEvidence(
                        f.director(), m, 7, SHA, List.of("build|pass|SUCCESS", "e2e|cancel|CANCELLED")));
        assertThatThrownBy(() -> f.company.deliveries().recordPullRequestMerged(f.director(), m, 7, SHA, "MERGE:x"))
                .extracting("code")
                .isEqualTo("CHECKS_NOT_GREEN");
    }

    @Test
    void aGreenMergeIsRecordedOnceWithItsApprovalAndSurvivesAReplay() {
        MissionId m = opened();
        f.record(f.company.deliveries().recordDeliveryEvidence(f.director(), m, 7, SHA, GREEN));
        for (CommandContext who : List.of(f.agent(CHRIS), f.system())) {
            assertThatThrownBy(() -> f.company.deliveries().recordPullRequestMerged(who, m, 7, SHA, "MERGE:x"))
                    .extracting("code")
                    .isEqualTo("FORBIDDEN");
        }
        var merged =
                f.record(f.company.deliveries().recordPullRequestMerged(f.director(), m, 7, SHA, "MERGE:pr:M-1#7"));
        assertThat(merged.getFirst().event()).isEqualTo(new PullRequestMerged(m, 7, SHA, "MERGE:pr:M-1#7"));
        assertThatThrownBy(() -> f.company.deliveries().recordPullRequestMerged(f.director(), m, 7, SHA, "MERGE:x"))
                .extracting("code")
                .isEqualTo("PULL_REQUEST_MERGED");

        assertThat(Company.replay(f.journal).deliveries().merge(m, 7)).hasValueSatisfying(x -> {
            assertThat(x.commit()).isEqualTo(SHA);
            assertThat(x.approval()).isEqualTo("MERGE:pr:M-1#7");
        });
    }

    @Test
    void aSimulatedMissionMergesNothing() {
        Fixture sim = new Fixture(Origin.SIMULATED);
        MissionId m = sim.mission();
        assertThatThrownBy(() -> sim.company.deliveries().recordPullRequestMerged(sim.director(), m, 7, SHA, "x"))
                .extracting("code")
                .isEqualTo("SIMULATED_MISSION");
    }
}
