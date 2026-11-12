package dev.floor42.server.iam;

import dev.floor42.server.Scaling;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link SafeUrls} reads a path an agent names: time grows linearly with its length. */
class SafeUrlsScalingTest {

    private static final Resource RESOURCE = new Resource(
            "kc",
            URI.create("https://kc.example.test"),
            "kc-api",
            List.of(),
            List.of("GET"),
            List.of("/api/"),
            1000,
            false);

    @Test
    void buildIsLinear() throws Exception {
        InetAddress pub = InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
        SafeUrls urls = new SafeUrls(host -> new InetAddress[] {pub});
        for (String u : List.of("\u0301", "\u200d", "\ufe0f", "a", "%2", "/", "?a=b&", "..", "x/"))
            Scaling.linear("SafeUrls " + u, "/api/", u, p -> {
                try {
                    urls.build(RESOURCE, "GET", p);
                } catch (IllegalArgumentException refused) {
                    // refused is a normal answer
                }
            });
    }
}
