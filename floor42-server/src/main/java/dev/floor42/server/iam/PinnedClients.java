package dev.floor42.server.iam;

import dev.floor42.server.iam.ResourceBroker.Refusal;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.SafeUrls.Target;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Connects to the address {@link SafeUrls} checked, never to a second resolution of the name (DNS rebinding): the URI
 * holds the IP literal, the Host header and the TLS server name (SNI and certificate check) hold the real host. The
 * JDK sets the Host header only when started with {@code -Djdk.httpclient.allowRestrictedHeaders=host}; without it a
 * public host is refused ({@code BROKER_PINNING_UNAVAILABLE}). A loopback test resource needs no Host header.
 */
@Singleton
public class PinnedClients {

    /** What to send the request to, and the Host header to set (null: none). */
    public record Pinned(HttpClient client, URI uri, String hostHeader) {}

    private final SSLContext ssl;
    private final BooleanSupplier hostHeaderAllowed;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    @Inject
    public PinnedClients() {
        this(
                defaultContext(),
                () -> System.getProperty("jdk.httpclient.allowRestrictedHeaders", "")
                        .toLowerCase(Locale.ROOT)
                        .contains("host"));
    }

    public PinnedClients(SSLContext ssl, BooleanSupplier hostHeaderAllowed) {
        this.ssl = ssl;
        this.hostHeaderAllowed = hostHeaderAllowed;
    }

    private static SSLContext defaultContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Pinned pin(Resource r, Target t) {
        URI uri = t.uri();
        boolean literal = t.host().equals(literal(t.address())) || uri.getHost().startsWith("[");
        if (r.local() || literal) return new Pinned(clientFor(null), at(uri, t.address()), null);
        if (!hostHeaderAllowed.getAsBoolean())
            throw new Refusal(
                    "BROKER_PINNING_UNAVAILABLE",
                    "The daemon was started without -Djdk.httpclient.allowRestrictedHeaders=host: a public host cannot"
                            + " be called at its checked address");
        String host = uri.getHost();
        String hostHeader = uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        return new Pinned(clientFor(host), at(uri, t.address()), hostHeader);
    }

    private HttpClient clientFor(String host) {
        return clients.computeIfAbsent(host == null ? "" : host, h -> {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(ssl);
            if (host != null) {
                SSLParameters p = new SSLParameters();
                p.setServerNames(List.of(new SNIHostName(host)));
                p.setEndpointIdentificationAlgorithm("HTTPS");
                b.sslParameters(p);
            }
            return b.build();
        });
    }

    private static URI at(URI uri, InetAddress address) {
        String ip = literal(address);
        String authority = (address instanceof Inet6Address ? "[" + ip + "]" : ip)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        return URI.create(uri.getScheme() + "://" + authority + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
    }

    private static String literal(InetAddress a) {
        String s = a.getHostAddress();
        int scope = s.indexOf('%');
        return scope < 0 ? s : s.substring(0, scope);
    }
}
