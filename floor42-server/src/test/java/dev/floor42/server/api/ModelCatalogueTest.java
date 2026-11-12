package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Setting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** One model catalogue: the one the daemon checks (Setting.RETRO_MODEL) is the one every panel offers (ui/models.ts). */
class ModelCatalogueTest {

    @Test
    void thePanelsOfferTheModelsTheDaemonAccepts() throws Exception {
        String ts = Files.readString(Path.of("../floor42-web/src/ui/models.ts"));
        String list = ts.substring(ts.indexOf("MODELS"));
        list = list.substring(list.indexOf("= ["), list.lastIndexOf(']') + 1);
        assertThat(Pattern.compile("'([^']+)'").matcher(list).results().map(r -> r.group(1)))
                .containsExactlyElementsOf(Setting.RETRO_MODEL.options());
    }
}
