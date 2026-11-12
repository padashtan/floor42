package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.iam.ResourceCatalogue.Resource;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeUrlsTest {

    private static final InetAddress PUBLIC = address(93, 184, 216, 34);

    private static InetAddress address(int... b) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) b[0], (byte) b[1], (byte) b[2], (byte) b[3]});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Resource resource(String base, boolean local) {
        return new Resource(
                "kc",
                URI.create(base),
                "kc-api",
                List.of(),
                List.of("GET", "POST"),
                List.of("/api/", "/exact"),
                1000,
                local);
    }

    private final SafeUrls urls = new SafeUrls(host -> new InetAddress[] {PUBLIC});

    private void refused(SafeUrls u, Resource r, String method, String path, String code) {
        assertThatThrownBy(() -> u.build(r, method, path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(code);
    }

    private void refused(String path, String code) {
        refused(urls, resource("https://kc.example.test", false), "GET", path, code);
    }

    @Test
    void acceptsAnAllowedPathOnTheCatalogueHost() {
        assertThat(urls.build(resource("https://kc.example.test", false), "get", "/api/users?first=0"))
                .isEqualTo(URI.create("https://kc.example.test/api/users?first=0"));
        assertThat(urls.build(resource("https://kc.example.test:8443", false), "POST", "/exact"))
                .isEqualTo(URI.create("https://kc.example.test:8443/exact"));
    }

    @Test
    void refusesEveryWayOutOfThePrefixOrTheHost() {
        for (String path : List.of(
                "/api/../admin",
                "/api/%2e%2e/x",
                "/api/%2E%2e/x",
                "/api/a%2fb",
                "/api/a%5cb",
                "/api/%252e%252e/x",
                "//evil.test/api",
                "/api//x",
                "/api@evil.test",
                "@evil.test/api",
                "/api/x#frag",
                "/api/a\\b",
                "/api/x\r\nHost: evil.test",
                "/api/x y",
                "/api/é",
                "/api/x%00",
                "api/x",
                "https://evil.test/api/x",
                "/admin",
                "/apix",
                "/exact/../x",
                "/exactly",
                "",
                "/")) refused(path, "PATH_NOT_ALLOWED");
        refused(null, "PATH_NOT_ALLOWED");
    }

    @Test
    void refusesAMethodThatIsNotListed() {
        refused(urls, resource("https://kc.example.test", false), "DELETE", "/api/x", "METHOD_NOT_ALLOWED");
        refused(urls, resource("https://kc.example.test", false), null, "/api/x", "METHOD_NOT_ALLOWED");
    }

    @Test
    void plainHttpOnlyForALocalResource() {
        refused(urls, resource("http://kc.example.test", false), "GET", "/api/x", "SCHEME_NOT_ALLOWED");
        refused(urls, resource("ftp://kc.example.test", true), "GET", "/api/x", "SCHEME_NOT_ALLOWED");
        SafeUrls loopback = new SafeUrls(host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
        assertThat(loopback.build(resource("http://127.0.0.1:9", true), "GET", "/api/x"))
                .isEqualTo(URI.create("http://127.0.0.1:9/api/x"));
    }

    @Test
    void refusesHostsThatResolveToInternalAddresses() {
        for (InetAddress a : List.of(
                address(127, 0, 0, 1),
                address(10, 1, 2, 3),
                address(192, 168, 0, 9),
                address(172, 16, 0, 1),
                address(169, 254, 169, 254),
                address(100, 64, 0, 1),
                address(0, 0, 0, 0),
                ipv6("::1"),
                ipv6("fe80::1"),
                ipv6("fd00::1"),
                ipv6("::ffff:127.0.0.1"),
                ipv6("::ffff:10.0.0.1"),
                ipv6("::10.0.0.1"),
                ipv6("::127.0.0.1"),
                ipv6("64:ff9b::7f00:1"),
                ipv6("64:ff9b::a9fe:a9fe"),
                ipv6("2002:7f00:1::"),
                ipv6("2002:0a00:1::1"),
                address(192, 0, 0, 8),
                address(198, 18, 0, 1),
                address(198, 19, 255, 1),
                address(240, 0, 0, 1),
                address(255, 255, 255, 255))) {
            refused(
                    new SafeUrls(host -> new InetAddress[] {a}),
                    resource("https://kc.example.test", false),
                    "GET",
                    "/api/x",
                    "HOST_NOT_ALLOWED");
        }
        // one bad address among good ones is enough (DNS answers with several)
        refused(
                new SafeUrls(host -> new InetAddress[] {PUBLIC, address(127, 0, 0, 1)}),
                resource("https://kc.example.test", false),
                "GET",
                "/api/x",
                "HOST_NOT_ALLOWED");
        refused(
                new SafeUrls(host -> {
                    throw new java.net.UnknownHostException(host);
                }),
                resource("https://kc.example.test", false),
                "GET",
                "/api/x",
                "HOST_NOT_ALLOWED");
    }

    @Test
    void aLocalResourceMustReallyBeLoopback() {
        refused(urls, resource("http://kc.example.test", true), "GET", "/api/x", "HOST_NOT_ALLOWED");
        refused(urls, resource("https://kc.example.test", true), "GET", "/api/x", "HOST_NOT_ALLOWED");
    }

    private static InetAddress ipv6(String text) {
        try {
            return InetAddress.getByName(text);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
