package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow.Step;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The step text rule (ADR-0052, section 2) on a fixture of real payloads, shared with the editor's Vitest test: each is
 * refused with its code and the position of its first hidden character, by the rule, by the check's constructor and by
 * the version 2 reader.
 */
class StepTextTest {

    record Case(String name, StepText.Field field, String text, String code, int position) {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture() throws IOException, Json.Refused {
        try (InputStream in = StepTextTest.class.getResourceAsStream("/workflow/hidden-step-texts.json")) {
            return (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), 100_000);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Case> cases() throws IOException, Json.Refused {
        return ((List<Object>) fixture().get("cases"))
                .stream()
                        .map(o -> (Map<String, Object>) o)
                        .map(m -> new Case(
                                (String) m.get("name"),
                                field((String) m.get("field")),
                                (String) m.get("text"),
                                (String) m.get("code"),
                                (Integer) m.get("position")))
                        .toList();
    }

    static StepText.Field field(String key) {
        for (StepText.Field f : StepText.Field.values()) if (f.key().equals(key)) return f;
        throw new IllegalArgumentException(key);
    }

    @Test
    void everyHiddenPayloadIsRefusedWithItsPosition() throws Exception {
        List<Case> cases = cases();
        assertThat(cases).hasSize(28);
        for (Case c : cases) {
            assertThat(StepText.check(c.field(), c.text()))
                    .as(c.name())
                    .contains(new StepText.Problem(c.code(), c.field(), c.position()));
        }
    }

    @Test
    void theCheckItselfAndTheVersionTwoReaderRefuseEachPayload() throws Exception {
        for (Case c : cases()) {
            assertThatThrownBy(() -> check(c.field(), c.text()))
                    .as(c.name())
                    .isInstanceOf(StepRefused.class)
                    .satisfies(e -> assertThat(((StepRefused) e).code()).isEqualTo(c.code()));
            String document = WorkflowDocument.write(
                            documentWith("x-" + c.field().key()))
                    .replace(
                            "x-" + c.field().key(),
                            Json.quote(c.text())
                                    .substring(1, Json.quote(c.text()).length() - 1));
            assertThat(WorkflowDocument.read(document, 2))
                    .as(c.name())
                    .isInstanceOf(WorkflowDocument.Reading.Refused.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void plainTextsLineFeedsInWhatItDoesAndAccentsAreAccepted() throws Exception {
        for (Object o : (List<Object>) fixture().get("accepted")) {
            Map<String, Object> m = (Map<String, Object>) o;
            assertThat(StepText.check(field((String) m.get("field")), (String) m.get("text")))
                    .isEmpty();
        }
    }

    /**
     * Independent of the rule's own list: every code point of each refused Unicode category, between two letters, is
     * refused; where NFKC leaves it in place, as a hidden character at its position. Every code point of the allowed
     * categories that NFKC leaves in place is accepted.
     */
    @Test
    void everyCodePointOfARefusedCategoryIsRefusedAndOfAnAllowedOneAccepted() {
        java.util.Set<Integer> refused = java.util.Set.of(
                (int) Character.NON_SPACING_MARK,
                (int) Character.ENCLOSING_MARK,
                (int) Character.FORMAT,
                (int) Character.PRIVATE_USE,
                (int) Character.UNASSIGNED,
                (int) Character.SURROGATE,
                (int) Character.CONTROL,
                (int) Character.LINE_SEPARATOR,
                (int) Character.PARAGRAPH_SEPARATOR,
                (int) Character.SPACE_SEPARATOR);
        int seen = 0;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp == ' ') continue;
            String text = "x" + new String(Character.toChars(cp)) + "y";
            boolean kept = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                    .equals(text);
            java.util.Optional<StepText.Problem> p = StepText.check(StepText.Field.WHY, text);
            if (refused.contains(Character.getType(cp))) {
                seen++;
                assertThat(p).as("U+%04X", cp).isPresent();
                if (kept) assertThat(p.get()).as("U+%04X", cp).isEqualTo(problem(StepText.Field.WHY, 2));
            } else if (invisible(cp)) {
                assertThat(p).as("U+%04X", cp).isPresent();
            } else if (kept) {
                assertThat(p).as("U+%04X", cp).isEmpty();
            }
        }
        assertThat(seen).isGreaterThan(900_000);
        // The line feed alone is a line of "what it does"; it is refused everywhere else.
        assertThat(StepText.check(StepText.Field.DOES, "x" + (char) 0x0A + "y")).isEmpty();
        assertThat(StepText.check(StepText.Field.TITLE, "x" + (char) 0x0A + "y"))
                .contains(problem(StepText.Field.TITLE, 2));
    }

    /**
     * The invisible carriers, written here apart from the rule: Unicode 16.0's Default_Ignorable_Code_Point
     * (DerivedCoreProperties.txt) and the symbols drawn blank (braille pattern blank, musical null notehead).
     */
    private static boolean invisible(int cp) {
        int[][] ranges = {
            {0xAD, 0xAD},
            {0x34F, 0x34F},
            {0x61C, 0x61C},
            {0x115F, 0x1160},
            {0x17B4, 0x17B5},
            {0x180B, 0x180F},
            {0x200B, 0x200F},
            {0x202A, 0x202E},
            {0x2060, 0x206F},
            {0x3164, 0x3164},
            {0xFE00, 0xFE0F},
            {0xFEFF, 0xFEFF},
            {0xFFA0, 0xFFA0},
            {0xFFF0, 0xFFF8},
            {0x1BCA0, 0x1BCA3},
            {0x1D173, 0x1D17A},
            {0xE0000, 0xE0FFF},
            {0x2800, 0x2800},
            {0x1D159, 0x1D159}
        };
        for (int[] r : ranges) if (cp >= r[0] && cp <= r[1]) return true;
        return false;
    }

    private static StepText.Problem problem(StepText.Field f, int position) {
        return new StepText.Problem("STEP_TEXT_HIDDEN_CHARACTER", f, position);
    }

    @Test
    void emptyAndOverlongTextsHaveTheirOwnCodes() {
        assertThat(StepText.check(StepText.Field.TITLE, " ").orElseThrow().code())
                .isEqualTo("STEP_TITLE_MISSING");
        assertThat(StepText.check(StepText.Field.WHY, null).orElseThrow().code())
                .isEqualTo("STEP_WHY_MISSING");
        assertThat(StepText.check(StepText.Field.DOES, "").orElseThrow().code()).isEqualTo("STEP_TEXT_MISSING");
        assertThat(StepText.check(StepText.Field.TITLE, "x".repeat(60))).isEmpty();
        assertThat(StepText.check(StepText.Field.TITLE, "x".repeat(61))
                        .orElseThrow()
                        .code())
                .isEqualTo("STEP_TEXT_TOO_LONG");
        // Code points, not UTF-16 units: 60 characters outside the basic plane are a title of 60.
        assertThat(StepText.check(StepText.Field.TITLE, "\ud83d\ude00".repeat(60)))
                .isEmpty();
        assertThat(StepText.check(StepText.Field.DOES, "x".repeat(2_001))
                        .orElseThrow()
                        .code())
                .isEqualTo("STEP_TEXT_TOO_LONG");
    }

    @Test
    void theEditorAndTheSaveCommandNormaliseBeforeTheRuleIsChecked() {
        String decomposed = "Cafe\u0301";
        assertThat(StepText.check(StepText.Field.TITLE, StepText.normalised(decomposed)))
                .isEmpty();
        assertThat(StepText.normalised(decomposed)).isEqualTo("Caf\u00e9");
    }

    private static Step.Check check(StepText.Field field, String text) {
        String title = field == StepText.Field.TITLE ? text : "Title";
        String does = field == StepText.Field.DOES ? text : "Does";
        String why = field == StepText.Field.WHY ? text : "Why";
        String handsOver = field == StepText.Field.HANDS_OVER ? text : "Hands";
        return new Step.Check("k", title, does, why, handsOver, true);
    }

    /** A valid workflow whose check holds a marker in place of the text under test. */
    private static Workflow documentWith(String marker) {
        Step.Check c = new Step.Check(
                "k",
                marker.endsWith("title") ? marker : "Title",
                marker.endsWith("does") ? marker : "Does",
                marker.endsWith("why") ? marker : "Why",
                marker.endsWith("handsOver") ? marker : "Hands",
                true);
        return new Workflow(
                "With a check",
                List.of(new Step.Approval(), new Step.Produce(), new Step.Review(), c, new Step.Accept()));
    }
}
