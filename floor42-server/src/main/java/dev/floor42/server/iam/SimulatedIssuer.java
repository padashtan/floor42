package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Issues the tokens of simulated sessions (demo, practice, fake Claude Code): own issuer, never the real one. */
@ApplicationScoped
public class SimulatedIssuer {

    public static final String ISSUER = "urn:floor42:simulated";
    public static final String KID = "simulated-1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KeyPair key = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is missing from the JDK", e);
        }
    }

    public PublicKey publicKey() {
        return key.getPublic();
    }

    public String mint(SessionClaims claims, Duration life) {
        Instant now = Instant.now();
        String agent = "simulated-" + claims.agent();
        ObjectNode header =
                JSON.createObjectNode().put("alg", "RS256").put("typ", "JWT").put("kid", KID);
        ObjectNode body = JSON.createObjectNode()
                .put("iss", ISSUER)
                .put("sub", "simulated-director")
                .put("azp", agent)
                .put("aud", "floor42-api")
                .put("iat", now.getEpochSecond())
                .put("exp", now.plus(life).getEpochSecond())
                .put("jti", UUID.randomUUID().toString());
        body.putObject("act").put("sub", agent).put("client_id", agent);
        body.putObject("floor42")
                .put("launch", claims.launch())
                .put("mission", claims.mission().value())
                .put("ticket", claims.ticket().value());
        return Jwts.sign(header, body, key.getPrivate());
    }
}
