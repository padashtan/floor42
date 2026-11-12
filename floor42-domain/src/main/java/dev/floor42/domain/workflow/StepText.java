package dev.floor42.domain.workflow;

import java.text.Normalizer;
import java.util.Optional;

/**
 * The rule every text of a director's step follows (ADR-0052, section 2), the same in the reader, the save command and
 * the editor (which mirrors it to show the problem inline; this one decides). A step text must equal its NFKC form, and
 * then holds only characters the screen shows as themselves: an allow-list of letters (L), digits and numbers (N),
 * punctuation (P), symbols (S), spacing marks (Mc), the plain space, and the line feed in {@link Field#DOES} only.
 * Everything else is refused, the model reading what the director cannot see: non-spacing and enclosing marks (Mn, Me:
 * variation selectors, the combining grapheme joiner), format characters (Cf: zero-width characters, bidirectional
 * controls, the byte order mark, tag characters), private use (Co), unassigned (Cn), lone surrogates (Cs), controls
 * (Cc), line and paragraph separators (Zl, Zp) and every space but U+0020. NFKC composes accented letters first, so a
 * French text passes. Default-ignorable code points ({@link #DEFAULT_IGNORABLE}, the Hangul fillers included) and
 * symbols drawn blank ({@link #BLANK_GLYPHS}) are refused too, whatever their category. Lengths count code points.
 *
 * <p>Cn follows the JDK's Unicode version: a later JDK only assigns more characters, so a text accepted once stays
 * accepted, and a character that moves into Cf is refused again by the same rule.
 */
public final class StepText {

    /** The texts of a step, with their longest length. */
    public enum Field {
        TITLE("title", 60),
        DOES("does", 2_000),
        WHY("why", 500),
        HANDS_OVER("handsOver", 500);

        private final String key;
        private final int max;

        Field(String key, int max) {
            this.key = key;
            this.max = max;
        }

        /** The document's key for this text. */
        public String key() {
            return key;
        }

        public int max() {
            return max;
        }
    }

    /**
     * Why a text is refused.
     *
     * @param code {@code STEP_TITLE_MISSING}, {@code STEP_WHY_MISSING}, {@code STEP_TEXT_MISSING},
     *     {@code STEP_TEXT_HIDDEN_CHARACTER}, {@code STEP_TEXT_NOT_NORMALISED} or {@code STEP_TEXT_TOO_LONG}
     * @param position the first hidden character, counted in code points from 1; 0 for the other codes
     */
    public record Problem(String code, Field field, int position) {}

    private StepText() {}

    /** The text's problem, empty when it may be saved as it is. */
    public static Optional<Problem> check(Field field, String text) {
        if (text == null || text.strip().isEmpty()) return Optional.of(new Problem(missing(field), field, 0));
        if (!Normalizer.normalize(text, Normalizer.Form.NFKC).equals(text)) {
            return Optional.of(new Problem("STEP_TEXT_NOT_NORMALISED", field, 0));
        }
        int position = 0;
        for (int cp : text.codePoints().toArray()) {
            position++;
            if (!shown(cp, field)) return Optional.of(new Problem("STEP_TEXT_HIDDEN_CHARACTER", field, position));
        }
        if (text.codePointCount(0, text.length()) > field.max()) {
            return Optional.of(new Problem("STEP_TEXT_TOO_LONG", field, 0));
        }
        return Optional.empty();
    }

    /**
     * The same rule for a free message of the director (an ask-back, ADR-0059): up to {@code max} code points, the line
     * feed allowed. The problem's code, empty when the text may be kept as it is.
     */
    public static Optional<String> checkMessage(String text, int max) {
        if (text == null || text.strip().isEmpty()) return Optional.of(missing(Field.DOES));
        if (!Normalizer.normalize(text, Normalizer.Form.NFKC).equals(text)) {
            return Optional.of("STEP_TEXT_NOT_NORMALISED");
        }
        if (text.codePoints().anyMatch(cp -> !shown(cp, Field.DOES))) return Optional.of("STEP_TEXT_HIDDEN_CHARACTER");
        return text.codePointCount(0, text.length()) > max ? Optional.of("STEP_TEXT_TOO_LONG") : Optional.empty();
    }

    /** The text the editor and the save command write: NFKC. The rule is then checked on what is written. */
    public static String normalised(String text) {
        return text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    /** Refuses the text with its code, as {@link Workflow}'s constructor does. */
    static void require(Field field, String text) {
        check(field, text).ifPresent(p -> {
            throw new StepRefused(
                    p.code(),
                    "The step's " + field.key() + " is refused (" + p.code()
                            + (p.position() > 0 ? " at character " + p.position() : "") + ")");
        });
    }

    private static String missing(Field field) {
        return switch (field) {
            case TITLE -> "STEP_TITLE_MISSING";
            case WHY -> "STEP_WHY_MISSING";
            case DOES, HANDS_OVER -> "STEP_TEXT_MISSING";
        };
    }

    /**
     * Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt, Unicode 16.0), as ranges: what a renderer may
     * draw as nothing. Most are Cf, Mn or Cn already; the Hangul fillers (U+115F, U+1160, U+3164, U+FFA0) are letters
     * (Lo) and would pass the categories alone.
     */
    static final int[][] DEFAULT_IGNORABLE = {
        {0x00AD, 0x00AD}, {0x034F, 0x034F}, {0x061C, 0x061C}, {0x115F, 0x1160}, {0x17B4, 0x17B5},
        {0x180B, 0x180F}, {0x200B, 0x200F}, {0x202A, 0x202E}, {0x2060, 0x206F}, {0x3164, 0x3164},
        {0xFE00, 0xFE0F}, {0xFEFF, 0xFEFF}, {0xFFA0, 0xFFA0}, {0xFFF0, 0xFFF8}, {0x1BCA0, 0x1BCA3},
        {0x1D173, 0x1D17A}, {0xE0000, 0xE0FFF}
    };

    /**
     * Symbols drawn as a blank glyph though not default-ignorable: the braille pattern blank (U+2800) and the musical
     * symbol null notehead (U+1D159), both So. The blank spaces of Zs (U+2000 to U+200A, U+205F, U+3000, U+00A0) are
     * refused as spaces other than U+0020, and most of them by NFKC already.
     */
    static final int[] BLANK_GLYPHS = {0x2800, 0x1D159};

    /**
     * Whether the code point may stand in a step text, whatever the field (the line feed aside): the table the web
     * reads, written by the server's contract test (step-text-table.json), so both sides agree by construction.
     */
    public static boolean allowed(int cp) {
        if (cp == ' ') return true;
        for (int[] r : DEFAULT_IGNORABLE) if (cp >= r[0] && cp <= r[1]) return false;
        for (int b : BLANK_GLYPHS) if (cp == b) return false;
        return shownCategory(cp);
    }

    /** The allow-list, read after NFKC: what the screen shows as itself. */
    private static boolean shown(int cp, Field field) {
        if (cp == 0x0A) return field == Field.DOES;
        return allowed(cp);
    }

    private static boolean shownCategory(int cp) {
        return switch (Character.getType(cp)) {
            case Character.UPPERCASE_LETTER,
                    Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER,
                    Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER,
                    Character.OTHER_NUMBER,
                    Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL,
                    Character.COMBINING_SPACING_MARK -> true;
            default -> false;
        };
    }
}
