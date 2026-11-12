package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every outcome of the wire maps to the right exception, and no secret or body text reaches a message. */
class IdpHttpTest {

    private static final String SECRET = "s3cr3t-value";
    private static final String FORM = "grant_type=client_credentials&client_secret=" + SECRET;

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private IdpExchanges exchanges;
    private IdpHttp http;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/t", x -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        exchanges = new IdpExchanges();
        http = new IdpHttp(new ObjectMapper(), exchanges);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpRequest.Builder request(int port) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/t"))
                .POST(HttpRequest.BodyPublishers.ofString(FORM));
    }

    private HttpRequest.Builder request() {
        return request(server.getAddress().getPort());
    }

    private void answer(int s, String b) {
        status = s;
        body = b;
    }

    @Test
    void aGoodAnswerIsParsedAndAnEmptyOneIsAnEmptyObject() {
        answer(200, "{\"a\":1}");
        assertThat(http.call("op", request(), FORM).path("a").asInt()).isEqualTo(1);
        answer(204, "");
        assertThat(http.call("op", request(), FORM).isEmpty()).isTrue();
    }

    @Test
    void clientErrorsAreRefusedWithTheirStatusAndOnlyAPlainErrorCode() {
        for (int s : new int[] {400, 401, 403}) {
            answer(s, "{\"error\":\"invalid_client\",\"error_description\":\"" + SECRET + "\"}");
            assertThatThrownBy(() -> http.call("op", request(), FORM))
                    .isInstanceOfSatisfying(IdpRefusedException.class, e -> {
                        assertThat(e.status()).isEqualTo(s);
                        assertThat(e.getMessage())
                                .contains("HTTP " + s, "invalid_client")
                                .doesNotContain(SECRET);
                    });
        }
        answer(400, "{\"error\":\"Free text, with the secret " + SECRET + "\"}");
        assertThatThrownBy(() -> http.call("op", request(), FORM))
                .isInstanceOf(IdpRefusedException.class)
                .hasMessage("Keycloak refused op: HTTP 400");
        answer(401, "<html>not json</html>");
        assertThatThrownBy(() -> http.call("op", request(), FORM))
                .isInstanceOf(IdpRefusedException.class)
                .hasMessage("Keycloak refused op: HTTP 401");
    }

    @Test
    void serverErrorsAndUnreadableAnswersAreUnavailable() {
        answer(503, "{\"error\":\"x\"}");
        assertThatThrownBy(() -> http.call("op", request(), FORM))
                .isInstanceOf(IdpUnavailableException.class)
                .hasMessage("Keycloak failed during op: HTTP 503");
        answer(200, "not json {");
        assertThatThrownBy(() -> http.call("op", request(), FORM))
                .isInstanceOf(IdpUnavailableException.class)
                .hasMessageNotContaining("not json");
    }

    @Test
    void aRedirectIsNeverFollowed() {
        answer(302, "");
        assertThatThrownBy(() -> http.call("op", request(), FORM))
                .isInstanceOfSatisfying(
                        IdpRefusedException.class, e -> assertThat(e.status()).isEqualTo(302));
    }

    @Test
    void aNetworkFailureIsUnavailableAndTheExchangeKeepsNoSecret() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        assertThatThrownBy(() -> http.call("op", request(closed), FORM))
                .isInstanceOf(IdpUnavailableException.class)
                .hasMessage("Keycloak unreachable during op");
        answer(401, "{\"error\":\"invalid_client\"}");
        assertThatThrownBy(() -> http.call("op", request(), FORM)).isInstanceOf(IdpRefusedException.class);
        assertThat(exchanges.list())
                .hasSize(2)
                .allSatisfy(x -> assertThat(x.request().toString()).doesNotContain(SECRET));
    }
}
