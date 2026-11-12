package dev.floor42.server.runtime.pty;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What ConPTY is given: a command line the C runtime splits back as it was, and a sorted environment block. */
class WindowsPtyTest {

    @Test
    void argumentsAreQuotedTheWayTheCRuntimeSplitsThem() {
        assertThat(WindowsPty.quote("plain")).isEqualTo("plain");
        assertThat(WindowsPty.quote("")).isEqualTo("\"\"");
        assertThat(WindowsPty.quote("C:\\Program Files\\x")).isEqualTo("\"C:\\Program Files\\x\"");
        assertThat(WindowsPty.quote("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(WindowsPty.quote("ends with \\")).isEqualTo("\"ends with \\\\\"");
        assertThat(WindowsPty.commandLine(List.of("C:\\npm\\claude.cmd", "--settings", "C:\\a b\\s.json")))
                .isEqualTo("cmd.exe /d /s /c \"C:\\npm\\claude.cmd --settings \"C:\\a b\\s.json\"\"");
    }

    @Test
    void theEnvironmentBlockIsSortedWithoutCase() {
        assertThat(WindowsPty.environmentBlock(Map.of("b", "2", "A", "1", "c", "3")))
                .isEqualTo("A=1\0b=2\0c=3\0");
    }
}
