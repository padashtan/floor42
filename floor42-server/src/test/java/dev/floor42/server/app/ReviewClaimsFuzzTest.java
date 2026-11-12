package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.floor42.domain.ReviewClaim;
import dev.floor42.server.FuzzBudget;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

/** {@link ReviewClaims}: the reviewer's text is stored as written, any text is read, in time. */
@Tag("fuzz")
class ReviewClaimsFuzzTest {

    private static final String[] DECORATIONS = {
        "", "- ", "* ", "+ ", "1. ", "12) ", "> ", "> - ", "  ", "\t", "**", "_"
    };
    // A label that is not a criterion ("on style") joins the value, so the value is not stored alone: none here.
    private static final String[] LABELS = {"", " C1", " C12", " (C2)", " criterion 3", " 4"};
    private static final String[] SEPARATORS = {": ", ":", " - ", " – ", " — ", "**: ", ":**"};
    private static final String[] RUNS = {" ", "\t", " \t", "*", "_", "> "};
    private static final String[] MIDS = {":", "", " - ", "x"};

    /** A value with free characters: no star (cut out of values), no line end, and not one the reader reads as a mark. */
    private static String freeValue(FuzzedDataProvider d) {
        String s = d.consumeString(80).replaceAll("[*\\r\\n\\u0085\\u2028\\u2029\\u000b\\u000c]", "x");
        if (d.consumeBoolean()) s = " ".repeat(d.consumeInt(0, 70)) + s + "\t".repeat(d.consumeInt(0, 40));
        return "w " + s;
    }

    @FuzzTest
    void aReservationValueIsKeptCharacterForCharacter(FuzzedDataProvider d) {
        String value = freeValue(d);
        if (value.toLowerCase(Locale.ROOT).contains("change")) return;
        String line = d.pickValue(DECORATIONS) + "RESERVATION" + d.pickValue(LABELS) + d.pickValue(SEPARATORS) + value;
        List<ReviewClaim> claims = ReviewClaims.of(line);
        assertThat(claims).as(line).hasSize(1);
        assertThat(claims.getFirst().text()).as(line).isEqualTo(value.strip());
    }

    @FuzzTest
    void anyTextIsReadWithinABudget(FuzzedDataProvider d) {
        String text = d.consumeRemainingAsString();
        FuzzBudget.within(Duration.ofSeconds(10), () -> {
            for (ReviewClaim c : ReviewClaims.of(text)) assertThat(c.text()).isNotNull();
        });
    }

    @FuzzTest
    void aLongRunStaysLinear(FuzzedDataProvider d) {
        String run = d.pickValue(RUNS).repeat(d.consumeInt(1000, 20000));
        String text = d.pickValue(DECORATIONS) + "RESERVATION" + run + d.pickValue(MIDS) + run + d.consumeString(5);
        FuzzBudget.within(Duration.ofSeconds(10), () -> ReviewClaims.of(text));
    }
}
