package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.floor42.server.iam.PinnedClients.Pinned;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.SafeUrls.Target;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The call goes to the checked address, with the real host in SNI, certificate check and Host header. */
class PinnedClientsTest {

    private static final String HOST = "test-resource.example";
    private static final InetAddress PUBLIC = address(93, 184, 216, 34);

    @TempDir
    Path dir;

    private HttpsServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static InetAddress address(int... b) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) b[0], (byte) b[1], (byte) b[2], (byte) b[3]});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Resource resource(String base, boolean local) {
        return new Resource(
                "tls", URI.create(base), "tls-api", List.of(), List.of("GET"), List.of("/api/"), 1000, local);
    }

    /** A keystore with a self-signed certificate for {@code name}, made by the JDK's own keytool. */
    private KeyStore keystoreFor(String name) throws Exception {
        Path file = dir.resolve(name + ".p12");
        Process p = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool")
                                .toString(),
                        "-genkeypair",
                        "-alias",
                        "k",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-dname",
                        "CN=" + name,
                        "-ext",
                        "san=dns:" + name,
                        "-validity",
                        "2",
                        "-keystore",
                        file.toString(),
                        "-storetype",
                        "PKCS12",
                        "-storepass",
                        "changeit")
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            ks.load(in, "changeit".toCharArray());
        }
        return ks;
    }

    private SSLContext trusting(KeyStore ks) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        Certificate cert = ks.getCertificate("k");
        trust.setCertificateEntry("t", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** A TLS server on the loopback address; it answers with the Host header it received. */
    private int serve(KeyStore ks) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", x -> {
            byte[] body = ("host=" + x.getRequestHeaders().getFirst("Host")).getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private String get(Pinned pinned) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(pinned.uri());
        if (pinned.hostHeader() != null) req.header("Host", pinned.hostHeader());
        return pinned.client().send(req.build(), BodyHandlers.ofString()).body();
    }

    private Pinned pin(PinnedClients pins, int port) {
        Resource r = resource("https://" + HOST + ":" + port, false);
        Target t = new Target(
                URI.create("https://" + HOST + ":" + port + "/api/x"), InetAddress.getLoopbackAddress(), HOST);
        return pins.pin(r, t);
    }

    @Test
    void connectsToTheCheckedAddressWithTheRealHostInSniCertificateAndHostHeader() throws Exception {
        KeyStore ks = keystoreFor(HOST);
        int port = serve(ks);
        Pinned pinned = pin(new PinnedClients(trusting(ks), () -> true), port);

        assertThat(pinned.uri().getHost()).isEqualTo("127.0.0.1");
        assertThat(pinned.hostHeader()).isEqualTo(HOST + ":" + port);
        assertThat(get(pinned)).isEqualTo("host=" + HOST + ":" + port);
    }

    @Test
    void aCertificateForAnotherNameIsRefused() throws Exception {
        KeyStore other = keystoreFor("other.example");
        int port = serve(other);
        Pinned pinned = pin(new PinnedClients(trusting(other), () -> true), port);

        assertThatThrownBy(() -> get(pinned)).isInstanceOf(IOException.class);
    }

    @Test
    void withoutTheRestrictedHeaderPropertyAPublicHostIsRefusedButLoopbackWorks() throws Exception {
        PinnedClients pins = new PinnedClients(SSLContext.getDefault(), () -> false);
        assertThatThrownBy(() -> pin(pins, 443))
                .isInstanceOfSatisfying(
                        Refusal.class, r -> assertThat(r.code()).isEqualTo("BROKER_PINNING_UNAVAILABLE"));
        Resource local = resource("http://127.0.0.1:9", true);
        Pinned p = pins.pin(
                local,
                new Target(URI.create("http://127.0.0.1:9/api/x"), InetAddress.getLoopbackAddress(), "127.0.0.1"));
        assertThat(p.hostHeader()).isNull();
    }

    @Test
    void aResolverThatFlipsAfterTheCheckDoesNotChangeWhereTheCallGoes() {
        AtomicInteger calls = new AtomicInteger();
        SafeUrls urls =
                new SafeUrls(host -> new InetAddress[] {calls.getAndIncrement() == 0 ? PUBLIC : address(127, 0, 0, 1)});
        Resource r = resource("https://" + HOST, false);

        Target target = urls.resolve(r, "GET", "/api/x");
        Pinned pinned = new PinnedClients(SSLContextHolder.DEFAULT, () -> true).pin(r, target);

        assertThat(calls.get()).as("the name is resolved once").isEqualTo(1);
        assertThat(pinned.uri()).isEqualTo(URI.create("https://93.184.216.34/api/x"));
        assertThat(pinned.hostHeader()).isEqualTo(HOST);
    }

    /** SSLContext.getDefault() without a checked exception in a field initializer. */
    private static final class SSLContextHolder {
        static final SSLContext DEFAULT;

        static {
            try {
                DEFAULT = SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
