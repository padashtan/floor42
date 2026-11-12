package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.app.RefusedException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the viewer is sent, decided by the bytes and not by the name alone (ADR-0051). */
class FileResponsesTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};
    static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @TempDir
    Path dir;

    Blob blob(String name, byte[] bytes) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return new Blob(name, file, false, bytes.length, Arrays.copyOf(bytes, Math.min(16, bytes.length)));
    }

    Blob blob(String name, String text) throws Exception {
        return blob(name, text.getBytes(StandardCharsets.UTF_8));
    }

    static String header(Response r, String name) {
        return String.valueOf(r.getHeaderString(name));
    }

    static int refusal(Blob blob) {
        try {
            FileResponses.of(blob, false, false);
        } catch (RefusedException e) {
            return e.status();
        }
        return 200;
    }

    @Test
    void anImageIsShownUnderTheImagePolicyWithTheTypeItsBytesSay() throws Exception {
        Response png = FileResponses.of(blob("photo.png", PNG), false, false);
        assertThat(png.getMediaType().toString()).isEqualTo("image/png");
        assertThat(header(png, "Content-Security-Policy"))
                .isEqualTo("sandbox; default-src 'none'; img-src 'self' blob: data:; style-src 'unsafe-inline'");
        assertThat(header(png, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(header(png, "Content-Disposition")).startsWith("inline;");
        // A JPEG named .png goes out as what it is.
        assertThat(FileResponses.of(blob("named.png", JPEG), false, false)
                        .getMediaType()
                        .toString())
                .isEqualTo("image/jpeg");
    }

    @Test
    void aCheckSaysWhatTheViewerWouldGetInA200WithoutTheBytes() throws Exception {
        Response refused = FileResponses.of(blob("fake.pdf", "<html><script>x</script></html>"), false, true);
        assertThat(refused.getStatus()).isEqualTo(200);
        assertThat(refused.getEntity())
                .isEqualTo(new FileResponses.ViewCheck(
                        false, null, "NOT_VIEWABLE", "fake.pdf cannot be shown here: download it"));
        Response shown = FileResponses.of(blob("photo.png", PNG), false, true);
        assertThat(((FileResponses.ViewCheck) shown.getEntity()).type()).isEqualTo("image/png");
        assertThat(((FileResponses.ViewCheck) shown.getEntity()).viewable()).isTrue();
    }

    @Test
    void htmlNamedAsAnImageOrAPdfIsNeverSentAsHtmlNorAsAnImage() throws Exception {
        String page = "<html><body onload=\"window.pwned=1\"><script>window.pwned=1</script></body></html>";
        assertThat(refusal(blob("trap.png", page))).isEqualTo(415);
        assertThat(refusal(blob("trap.jpg", page))).isEqualTo(415);
        assertThat(refusal(blob("trap.pdf", page))).isEqualTo(415);
        assertThat(refusal(blob("trap.svg", page))).isEqualTo(415);
    }

    @Test
    void anSvgWithAScriptIsAnImageOnlyAndDownloadsWhenOpenedOnItsOwn() throws Exception {
        Response svg = FileResponses.of(
                blob(
                        "evil.svg",
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"window.pwned=1\">"
                                + "<script>window.pwned=1</script><rect width=\"4\" height=\"4\"/></svg>"),
                false,
                false);
        assertThat(svg.getMediaType().toString()).isEqualTo("image/svg+xml");
        assertThat(header(svg, "Content-Disposition")).startsWith("attachment;");
        // Pinned: the old policy for an SVG was the bare sandbox; this one is the images' own, and nothing wider.
        assertThat(header(svg, "Content-Security-Policy"))
                .isEqualTo("sandbox; default-src 'none'; img-src 'self' blob: data:; style-src 'unsafe-inline'");
        assertThat(header(svg, "X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void aPdfAnUnknownKindATextAndADownload() throws Exception {
        Response pdf = FileResponses.of(blob("report.pdf", "%PDF-1.4\n% fake\n"), false, false);
        assertThat(pdf.getMediaType().toString()).isEqualTo("application/pdf");
        assertThat(header(pdf, "Content-Security-Policy"))
                .contains("frame-ancestors 'self'")
                .doesNotContain("script");
        assertThat(refusal(blob("archive.zip", PNG))).isEqualTo(415);
        Response html = FileResponses.of(blob("page.html", "<script>alert(1)</script>"), false, false);
        assertThat(html.getMediaType().toString()).startsWith("text/plain");
        Response download = FileResponses.of(blob("page.html", "<script>alert(1)</script>"), true, false);
        assertThat(download.getMediaType().toString()).isEqualTo("application/octet-stream");
        assertThat(header(download, "Content-Disposition")).startsWith("attachment;");
        assertThat(header(download, "Content-Security-Policy")).startsWith("sandbox");
    }

    @Test
    void aFileAboveTheCapIsRefusedForTheViewer() throws Exception {
        Blob big = new Blob("big.png", Files.write(dir.resolve("big.png"), PNG), false, 26L * 1024 * 1024, PNG);
        assertThatThrownBy(() -> FileResponses.of(big, false, false))
                .isInstanceOfSatisfying(
                        RefusedException.class, e -> assertThat(e.status()).isEqualTo(413));
    }
}
