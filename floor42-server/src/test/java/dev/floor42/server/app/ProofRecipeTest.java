package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The recipe a producer leaves in PROVE.md (ADR-0058), read the same way every time. */
class ProofRecipeTest {

    static ProofRecipe parse(String text) {
        return ProofRecipe.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    static final String COMPOSE = """
            # Proof
            Some words the parser ignores.

            ## Start the service
            ```sh director
            docker compose up -d
            ```
            Expect exit: 0

            ## The health route answers
            ```bash
            curl -s localhost:8080/health
            ```
            Expect output: {"status":"UP"}
            Expect output: UP
            """;

    @Test
    void eachHeadingIsAStepWithItsCommandAndWhatItMustShow() {
        ProofRecipe r = parse(COMPOSE);
        assertThat(r.state()).isEqualTo(ProofRecipe.FOUND);
        assertThat(r.problems()).isEmpty();
        assertThat(r.digest()).hasSize(16);
        assertThat(r.steps()).hasSize(2);
        ProofRecipe.Step first = r.steps().getFirst();
        assertThat(first.title()).isEqualTo("Start the service");
        assertThat(first.command()).isEqualTo("docker compose up -d");
        assertThat(first.expectExit()).isZero();
        assertThat(first.director()).isTrue();
        assertThat(first.runnable()).isTrue();
        ProofRecipe.Step second = r.steps().get(1);
        assertThat(second.index()).isEqualTo(2);
        assertThat(second.director()).isFalse();
        assertThat(second.expectExit()).isNull();
        assertThat(second.expectOutput()).containsExactly("{\"status\":\"UP\"}", "UP");
    }

    @Test
    void windowsLineEndingsReadTheSame() {
        assertThat(parse(COMPOSE.replace("\n", "\r\n")).steps())
                .isEqualTo(parse(COMPOSE).steps());
    }

    @Test
    void aStepThatCannotBeJudgedOrRunSaysWhy() {
        ProofRecipe r = parse("""
                ## No expectation
                ```sh
                ls
                ```
                ## No command
                Expect exit: 0
                ## Two commands
                ```sh
                a
                ```
                ```sh
                b
                ```
                Expect exit: 1
                Expect exit: 2
                ## Bad code
                ```sh
                c
                ```
                Expect exit: yes
                ## A Python fence is not a command
                ```python
                print(1)
                ```
                Expect exit: 0
                """);
        assertThat(r.steps())
                .extracting(ProofRecipe.Step::problems)
                .containsExactly(
                        java.util.List.of("NO_EXPECTATION"),
                        java.util.List.of("NO_COMMAND"),
                        java.util.List.of("TWO_COMMANDS", "TWO_EXIT_CODES"),
                        java.util.List.of("BAD_EXIT_CODE"),
                        java.util.List.of("NO_COMMAND"));
        assertThat(r.steps()).noneMatch(ProofRecipe.Step::runnable);
    }

    @Test
    void aFileWithoutStepsOrTooLargeOrWithTooManyStepsIsAProblem() {
        assertThat(parse("Just prose.").problems()).containsExactly("NO_STEPS");
        assertThat(ProofRecipe.parse(new byte[ProofRecipe.MAX_BYTES + 1]).problems())
                .containsExactly("TOO_LARGE");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < ProofRecipe.MAX_STEPS + 1; i++)
            many.append("## S").append(i).append("\n```sh\necho\n```\nExpect exit: 0\n");
        ProofRecipe r = parse(many.toString());
        assertThat(r.problems()).containsExactly("TOO_MANY_STEPS");
        assertThat(r.steps()).hasSize(ProofRecipe.MAX_STEPS);
    }

    @Test
    void aLongCommandIsCutAndNotRun() {
        ProofRecipe r = parse("## Long\n```sh\n" + "x".repeat(ProofRecipe.MAX_COMMAND + 1) + "\n```\nExpect exit: 0\n");
        assertThat(r.steps().getFirst().problems()).containsExactly("COMMAND_TOO_LONG");
        assertThat(r.steps().getFirst().command()).hasSize(ProofRecipe.MAX_COMMAND);
    }

    @Test
    void aCommandThatWouldPasteOtherThanItReadsIsRefused() {
        for (String hidden : java.util.List.of("\u202e", "\u2066", "\u200b", "\ufeff", "\u0007", "\u001B[2K")) {
            ProofRecipe r = parse("## Step\n```sh\nrm -rf build " + hidden + "&& ls\n```\nExpect exit: 0\n");
            assertThat(r.steps().getFirst().problems()).as(hidden).containsExactly("HIDDEN_CHARACTER");
        }
        assertThat(parse("## Step\n```sh\nprintf 'a\tb'\necho done\n```\nExpect exit: 0\n")
                        .steps()
                        .getFirst()
                        .problems())
                .isEmpty();
    }

    @Test
    void theDigestFollowsTheText() {
        assertThat(parse(COMPOSE).digest()).isEqualTo(parse(COMPOSE).digest());
        assertThat(parse(COMPOSE + "\n").digest()).isNotEqualTo(parse(COMPOSE).digest());
    }
}
