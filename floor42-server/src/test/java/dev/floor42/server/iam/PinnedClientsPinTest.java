package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.iam.PinnedClients.Pinned;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.SafeUrls.Target;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/** The address that was checked is the address that is called; the real host only rides in the Host header and SNI. */
class PinnedClientsPinTest {

    private static final InetAddress PUBLIC = ip(93, 184, 216, 34);

    private static InetAddress ip(int... b) {
        try {
            byte[] x = new byte[b.length];
            for (int i = 0; i < b.length; i++) x[i] = (byte) b[i];
            return InetAddress.getByAddress(x);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Resource resource(boolean local) {
        return new Resource(
                "r", URI.create("https://h.example"), "aud", List.of(), List.of("GET"), List.of("/"), 100, local);
    }

    private PinnedClients pins(boolean headerAllowed) throws Exception {
        return new PinnedClients(SSLContext.getDefault(), () -> headerAllowed);
    }

    @Test
    void aPublicHostIsCalledAtItsCheckedIpWithTheRealHostHeader() throws Exception {
        Target t = new Target(URI.create("https://api.example.com:8443/v1/x?q=a%20b"), PUBLIC, "api.example.com");
        Pinned p = pins(true).pin(resource(false), t);
        assertThat(p.uri()).isEqualTo(URI.create("https://93.184.216.34:8443/v1/x?q=a%20b"));
        assertThat(p.hostHeader()).isEqualTo("api.example.com:8443");
        Pinned noPort = pins(true)
                .pin(resource(false), new Target(URI.create("https://api.example.com/v1"), PUBLIC, "api.example.com"));
        assertThat(noPort.uri()).isEqualTo(URI.create("https://93.184.216.34/v1"));
        assertThat(noPort.hostHeader()).isEqualTo("api.example.com");
    }

    @Test
    void withoutTheRestrictedHeaderPropertyAPublicHostIsRefused() throws Exception {
        Target t = new Target(URI.create("https://api.example.com/v1"), PUBLIC, "api.example.com");
        assertThatThrownBy(() -> pins(false).pin(resource(false), t))
                .isInstanceOfSatisfying(
                        Refusal.class, e -> assertThat(e.code()).isEqualTo("BROKER_PINNING_UNAVAILABLE"));
    }

    @Test
    void aLocalResourceOrAnIpLiteralNeedsNoHostHeader() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        Pinned local = pins(false).pin(resource(true), new Target(URI.create("http://localhost:9/p"), lo, "localhost"));
        assertThat(local.hostHeader()).isNull();
        assertThat(local.uri()).isEqualTo(URI.create("http://127.0.0.1:9/p"));
        Pinned literal = pins(false)
                .pin(resource(false), new Target(URI.create("https://93.184.216.34/p"), PUBLIC, "93.184.216.34"));
        assertThat(literal.hostHeader()).isNull();
        assertThat(literal.uri()).isEqualTo(URI.create("https://93.184.216.34/p"));
    }

    @Test
    void anIpv6AddressIsBracketedAndItsScopeDropped() throws Exception {
        Inet6Address v6 = (Inet6Address) InetAddress.getByName("2606:4700::1111");
        Pinned p =
                pins(true).pin(resource(false), new Target(URI.create("https://v6.example:444/x"), v6, "v6.example"));
        assertThat(p.uri().getHost()).isEqualTo("[2606:4700:0:0:0:0:0:1111]");
        assertThat(p.uri().getPort()).isEqualTo(444);
        Inet6Address scoped = Inet6Address.getByAddress("v6.example", v6.getAddress(), 3);
        Pinned s =
                pins(true).pin(resource(false), new Target(URI.create("https://v6.example/x"), scoped, "v6.example"));
        assertThat(s.uri().toString()).doesNotContain("%");
        Pinned lit = pins(false)
                .pin(resource(false), new Target(URI.create("https://[2606:4700::1111]/x"), v6, "[2606:4700::1111]"));
        assertThat(lit.hostHeader()).isNull();
    }

    @Test
    void theClientNeverFollowsARedirectNorUsesAProxyAndGivesUpToConnectQuickly() throws Exception {
        Pinned p = pins(true).pin(resource(false), new Target(URI.create("https://a.example/x"), PUBLIC, "a.example"));
        HttpClient c = p.client();
        assertThat(c.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(c.proxy().orElseThrow().select(URI.create("https://a.example/")))
                .containsExactly(java.net.Proxy.NO_PROXY);
        assertThat(c.connectTimeout()).contains(Duration.ofSeconds(5));
    }

    @Test
    void theRealHostRidesInTheTlsServerNameAndTheCertificateIsCheckedAgainstIt() throws Exception {
        Pinned p = pins(true).pin(resource(false), new Target(URI.create("https://a.example/x"), PUBLIC, "a.example"));
        var params = p.client().sslParameters();
        assertThat(params.getServerNames()).containsExactly(new SNIHostName("a.example"));
        assertThat(params.getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        Pinned local = pins(true)
                .pin(
                        resource(true),
                        new Target(URI.create("http://localhost/x"), InetAddress.getLoopbackAddress(), "localhost"));
        assertThat(local.client().sslParameters().getServerNames()).isNull();
    }

    @Test
    void oneClientPerHostIsReusedAndHostsDoNotShareOne() throws Exception {
        PinnedClients pins = pins(true);
        Pinned a1 = pins.pin(resource(false), new Target(URI.create("https://a.example/x"), PUBLIC, "a.example"));
        Pinned a2 = pins.pin(resource(false), new Target(URI.create("https://a.example/y"), PUBLIC, "a.example"));
        Pinned b = pins.pin(resource(false), new Target(URI.create("https://b.example/x"), PUBLIC, "b.example"));
        Pinned l = pins.pin(
                resource(true),
                new Target(URI.create("http://localhost/x"), InetAddress.getLoopbackAddress(), "localhost"));
        assertThat(a1.client()).isSameAs(a2.client()).isNotSameAs(b.client()).isNotSameAs(l.client());
    }

    @Test
    void thePropertyOfTheDaemonDecidesWhetherAPublicHostCanBePinned() {
        String key = "jdk.httpclient.allowRestrictedHeaders";
        String old = System.getProperty(key);
        Target t = new Target(URI.create("https://api.example.com/v1"), PUBLIC, "api.example.com");
        try {
            System.clearProperty(key);
            assertThatThrownBy(() -> new PinnedClients().pin(resource(false), t))
                    .isInstanceOf(Refusal.class);
            System.setProperty(key, "Content-Length,HOST");
            assertThat(new PinnedClients().pin(resource(false), t).hostHeader()).isEqualTo("api.example.com");
            System.setProperty(key, "content-length");
            assertThatThrownBy(() -> new PinnedClients().pin(resource(false), t))
                    .isInstanceOf(Refusal.class);
        } finally {
            if (old == null) System.clearProperty(key);
            else System.setProperty(key, old);
        }
    }

    @Test
    void portZeroIsAPortNotAnAbsentOne() throws Exception {
        Pinned p =
                pins(true).pin(resource(false), new Target(URI.create("https://a.example:0/x"), PUBLIC, "a.example"));
        assertThat(p.hostHeader()).isEqualTo("a.example:0");
        assertThat(p.uri().getPort()).isZero();
    }
}
