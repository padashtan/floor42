package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SessionRiskTest {

    static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");
    static final Map<String, RiskReason> CODES = Map.of(
            "P", RiskReason.POLICY_DENIED,
            "C", RiskReason.COMMAND_DENIED,
            "O", RiskReason.OUTSIDE_WORKSPACE,
            "B", RiskReason.BROKER_PATH_NOT_ALLOWED,
            "M", RiskReason.BROKER_METHOD_NOT_ALLOWED,
            "I", RiskReason.INJECTION_MARKER,
            "U", RiskReason.UNTRUSTED_READ,
            "L", RiskReason.OWN_LEASH);

    /** Runs "CODE@seconds ..." with action ids a1, a2, ...; returns the raise (or null) per signal. */
    static List<RiskRaise> run(SessionRisk risk, String sequence) {
        List<RiskRaise> out = new ArrayList<>();
        int n = 0;
        for (String item : sequence.split(" ")) {
            String[] p = item.split("@");
            n++;
            RiskSignal s = new RiskSignal(CODES.get(p[0]), T0.plusSeconds(Long.parseLong(p[1])), new ActionId("a" + n));
            out.add(risk.add(s).orElse(null));
        }
        return out;
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "P@0|NOTICE",
                "P@0 P@600|NOTICE ALERT",
                "O@0|ALERT",
                "B@0|ALERT",
                "P@0 C@60 P@120|NOTICE ALERT QUARANTINE",
                "P@0 P@180 P@360|NOTICE ALERT -",
                "P@0 P@150 P@300|NOTICE ALERT QUARANTINE",
                "L@0|QUARANTINE",
                "U@0|-",
                "U@0 M@30|- QUARANTINE",
                "I@0|NOTICE",
                "I@0 P@60|NOTICE QUARANTINE",
                "L@0 P@10 O@20|QUARANTINE - -",
                "M@0 O@10|ALERT -",
                "O@0 O@10 O@20|ALERT - QUARANTINE"
            })
    void levelsFollowTheTable(String sequence, String expected) {
        List<RiskRaise> raises = run(new SessionRisk(), sequence);
        List<String> got =
                raises.stream().map(r -> r == null ? "-" : r.level().name()).toList();
        assertThat(String.join(" ", got)).isEqualTo(expected);
    }

    @Test
    void theQuarantineOfABurstNamesItsReasons() {
        RiskRaise last = run(new SessionRisk(), "P@0 C@60 P@120").get(2);
        assertThat(last.reasons())
                .containsExactly(RiskReason.POLICY_DENIED, RiskReason.COMMAND_DENIED, RiskReason.BURST);
        assertThat(last.windowStart()).isEqualTo(T0);
        assertThat(last.actions()).extracting(ActionId::value).containsExactly("a1", "a2", "a3");
    }

    @Test
    void aTaintedRefusalNamesTheRead() {
        RiskRaise last = run(new SessionRisk(), "U@0 M@30").get(1);
        assertThat(last.reasons())
                .containsExactly(
                        RiskReason.UNTRUSTED_READ,
                        RiskReason.BROKER_METHOD_NOT_ALLOWED,
                        RiskReason.TAINTED_THEN_REFUSED);
    }

    @Test
    void onlyTheLatestTenActionsAreKept() {
        SessionRisk risk = new SessionRisk();
        RiskRaise first = null;
        for (int i = 1; i <= 12; i++) {
            var r = risk.add(new RiskSignal(RiskReason.POLICY_DENIED, T0.plusSeconds(600L * i), new ActionId("a" + i)));
            if (r.isPresent() && r.get().level() == RiskLevel.ALERT) {
                first = r.get();
            }
        }
        assertThat(first.level()).isEqualTo(RiskLevel.ALERT);
        assertThat(first.actions()).extracting(ActionId::value).containsExactly("a1", "a2");
        assertThat(risk.level()).isEqualTo(RiskLevel.ALERT);
        // the leash signal carries no action id, so the kept ids stay a3..a12
        RiskRaise q = risk.add(new RiskSignal(RiskReason.OWN_LEASH, T0.plusSeconds(7300), null))
                .orElseThrow();
        assertThat(q.level()).isEqualTo(RiskLevel.QUARANTINE);
        assertThat(q.actions())
                .extracting(ActionId::value)
                .containsExactly("a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11", "a12");
    }

    @Test
    void derivedReasonsCannotBeGiven() {
        SessionRisk risk = new SessionRisk();
        for (RiskReason r : List.of(RiskReason.BURST, RiskReason.TAINTED_THEN_REFUSED)) {
            assertThatThrownBy(() -> risk.add(new RiskSignal(r, T0, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "Read,.claude/settings.json,true",
        "Edit,src/.git/hooks/pre-commit,true",
        "Read,.env,true",
        "Read,.env.example,false",
        "Bash,printenv,true",
        "Bash,env | grep TOKEN,true",
        "Bash,env FOO=1 make,false",
        "Bash,set,true",
        "Bash,set -e,false",
        "Write,docs/settings.md,false",
        "Read,src/Main.java,false"
    })
    void ownLeashTable(String tool, String summary, boolean expected) {
        assertThat(SessionRisk.ownLeash(tool, summary)).isEqualTo(expected);
    }

    @Test
    void brokerCodes() {
        assertThat(RiskReason.broker("PATH_NOT_ALLOWED")).contains(RiskReason.BROKER_PATH_NOT_ALLOWED);
        assertThat(RiskReason.broker("METHOD_NOT_ALLOWED")).contains(RiskReason.BROKER_METHOD_NOT_ALLOWED);
        assertThat(RiskReason.broker("RESOURCE_NOT_GRANTED")).contains(RiskReason.BROKER_RESOURCE_NOT_GRANTED);
        assertThat(RiskReason.broker("RESOURCE_UNKNOWN")).contains(RiskReason.BROKER_RESOURCE_UNKNOWN);
        assertThat(RiskReason.broker("IDENTITY_UNAVAILABLE")).isEmpty();
        assertThat(RiskReason.broker("NOT_AUTHORIZED")).isEmpty();
        assertThat(RiskReason.broker("")).isEmpty();
    }
}
