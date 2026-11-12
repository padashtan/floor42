package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.Delivered.Blob;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A file is checked, then read only from the channel opened at the check, cut at the size measured then. */
class BlobBytesTest {

    @TempDir
    Path dir;

    static String sent(Blob blob) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        blob.copyTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aFileGrownAfterTheCheckSendsNoMoreThanWasChecked() throws Exception {
        Path file = Files.writeString(dir.resolve("photo.png"), "checked");
        Blob blob = BlobSource.onDisk(dir, file, "photo.png");
        try {
            Files.writeString(file, "<html><script>x</script></html>", StandardOpenOption.APPEND);
            assertThat(blob.size()).isEqualTo(7);
            assertThat(sent(blob)).isEqualTo("checked");
            assertThat(new String(blob.first(1024), StandardCharsets.UTF_8)).isEqualTo("checked");
            assertThat(new String(blob.head(), StandardCharsets.UTF_8)).isEqualTo("checked");
        } finally {
            blob.release();
        }
    }

    @Test
    void aFileSwappedAfterTheCheckIsNotWhatGoesOut() throws Exception {
        Path file = Files.writeString(dir.resolve("notes.md"), "the checked file");
        Blob blob = BlobSource.onDisk(dir, file, "notes.md");
        try {
            Files.move(file, dir.resolve("moved.md"));
            Files.writeString(file, "another file put in its place");
            assertThat(sent(blob)).isEqualTo("the checked file");
        } finally {
            blob.release();
        }
    }

    @Test
    void releaseClosesTheChannelAndDeletesATemporaryCopy() throws Exception {
        Path tmp = Files.writeString(dir.resolve("copy.bin"), "abc");
        Blob blob = BlobBytes.open("copy.bin", tmp, true);
        blob.release();
        assertThat(blob.channel().isOpen()).isFalse();
        assertThat(tmp).doesNotExist();
    }
}
