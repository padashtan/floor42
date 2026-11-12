package dev.floor42.server.iam;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

/** A one-document JWKS endpoint for tests that need keys the fake Keycloak would not serve (small, kid-less). */
final class JwksServer implements AutoCloseable {

    private final HttpServer server;

    JwksServer(PublicKey key, String kid) throws IOException {
        this(rsaJwk(key, kid));
    }

    /** {@code keysJson} is the content of the {@code keys} array. */
    JwksServer(String keysJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = ("{\"keys\":[" + keysJson + "]}").getBytes(StandardCharsets.UTF_8);
        server.createContext("/certs", x -> {
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/certs");
    }

    static String rsaJwk(PublicKey key, String kid) {
        RSAPublicKey k = (RSAPublicKey) key;
        String n = Jwts.base64url(unsigned(k.getModulus().toByteArray()));
        String e = Jwts.base64url(unsigned(k.getPublicExponent().toByteArray()));
        String id = kid == null ? "" : "\"kid\":\"" + kid + "\",";
        return "{\"kty\":\"RSA\",\"use\":\"sig\"," + id + "\"n\":\"" + n + "\",\"e\":\"" + e + "\"}";
    }

    private static byte[] unsigned(byte[] b) {
        return b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
