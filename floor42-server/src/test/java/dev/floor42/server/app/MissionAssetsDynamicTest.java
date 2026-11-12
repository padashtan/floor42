package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.runtime.Sandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** With the sandbox on, a skill that runs a command when it loads is never given to a session (ADR-0028). */
class MissionAssetsDynamicTest {

    @TempDir
    Path dir;

    private MissionAssets assets(boolean sandboxOn) {
        MissionAssets a = new MissionAssets();
        a.json = new ObjectMapper();
        a.sandbox = new Sandbox() {
            @Override
            public boolean enabled() {
                return sandboxOn;
            }
        };
        return a;
    }

    private Path plugin() throws Exception {
        Path src = dir.resolve("src");
        for (String[] s : new String[][] {
            {"fine", "---\nname: fine\ndescription: d\n---\nPlain.\n"},
            {"live", "---\nname: live\ndescription: d\n---\nNow: !`date`\n"}
        }) {
            Files.createDirectories(src.resolve("skills").resolve(s[0]));
            Files.writeString(src.resolve("skills").resolve(s[0]).resolve("SKILL.md"), s[1]);
        }
        return src;
    }

    @Test
    void onlyTheSkillWithoutACommandIsGiven() throws Exception {
        List<String> notes = new ArrayList<>();
        Path dest = dir.resolve("dest");

        int given = assets(true)
                .layOut(
                        plugin(),
                        dest,
                        "p",
                        List.<String[]>of(new String[] {"skill", "fine"}, new String[] {"skill", "live"}),
                        notes);

        assertThat(given).isEqualTo(1);
        assertThat(dest.resolve("skills/fine/SKILL.md")).exists();
        assertThat(dest.resolve("skills/live")).doesNotExist();
        assertThat(notes).singleElement().asString().contains("p:live").contains("runsCommand");
    }

    @Test
    void aSkillThatCannotBeReadIsLeftOutToo() throws Exception {
        Path src = plugin();
        Files.delete(src.resolve("skills/live/SKILL.md"));
        List<String> notes = new ArrayList<>();

        int given = assets(true)
                .layOut(src, dir.resolve("dest"), "p", List.<String[]>of(new String[] {"skill", "live"}), notes);

        assertThat(given).isZero();
    }

    @Test
    void withoutTheSandboxTheSkillIsGivenAsBefore() throws Exception {
        List<String> notes = new ArrayList<>();

        int given = assets(false)
                .layOut(plugin(), dir.resolve("dest"), "p", List.<String[]>of(new String[] {"skill", "live"}), notes);

        assertThat(given).isEqualTo(1);
        assertThat(notes).isEmpty();
    }
}
