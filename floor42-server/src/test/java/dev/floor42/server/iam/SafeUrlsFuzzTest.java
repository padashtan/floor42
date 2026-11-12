package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Tag;

/**
 * {@link SafeUrls#build}: whatever path an agent names, the call either is refused with an {@code
 * IllegalArgumentException} or goes to the catalogue's own scheme, host and port, with no user info, under an allowed
 * prefix, and without a segment that moves the path.
 */
@Tag("fuzz")
class SafeUrlsFuzzTest {

    private static final InetAddress PUBLIC = address();
    private static final Resource RESOURCE = new Resource(
            "kc",
            URI.create("https://kc.example.test:8443"),
            "kc-api",
            List.of(),
            List.of("GET"),
            List.of("/api/", "/exact"),
            1000,
            false);
    private static final SafeUrls URLS = new SafeUrls(host -> new InetAddress[] {PUBLIC});
    private static final String[] PIECES = {
        "/api/",
        "/exact",
        "/",
        "..",
        ".",
        "%2e",
        "%2E%2e",
        "%2f",
        "%5c",
        "%25",
        "%00",
        "@",
        "#",
        "?",
        "\\",
        "//",
        ":",
        "x",
        "users",
        "?a=b",
        "evil.test",
        "\t",
        " ",
        "é",
        "\u0085",
        "[",
        "]"
    };

    private static InetAddress address() {
        try {
            return InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @FuzzTest
    void aBuiltAddressStaysOnTheCatalogueHostAndUnderAPrefix(FuzzedDataProvider d) {
        StringBuilder b = new StringBuilder();
        int n = d.consumeInt(0, 10);
        for (int i = 0; i < n && d.remainingBytes() > 0; i++)
            b.append(d.consumeInt(0, 2) == 0 ? d.consumeString(4) : d.pickValue(PIECES));
        String path = b.toString();
        URI uri;
        try {
            uri = URLS.build(RESOURCE, "GET", path);
        } catch (IllegalArgumentException refused) {
            return;
        }
        assertThat(uri.getScheme()).as(path).isEqualTo("https");
        assertThat(uri.getRawAuthority()).as(path).isEqualTo("kc.example.test:8443");
        assertThat(uri.getRawUserInfo()).as(path).isNull();
        String raw = uri.getRawPath();
        assertThat(raw.startsWith("/api/") || raw.equals("/exact") || raw.startsWith("/exact/"))
                .as(path)
                .isTrue();
        assertThat(raw).as(path).doesNotContain("//").doesNotContain("..").doesNotContain("\\");
        assertThat(raw.toLowerCase())
                .as(path)
                .doesNotContain("%2e")
                .doesNotContain("%2f")
                .doesNotContain("%5c");
        // A lone "." segment is allowed: with ".." refused it cannot leave the prefix (found by the first fuzz run).
    }
}
