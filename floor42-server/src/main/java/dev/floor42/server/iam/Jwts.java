package dev.floor42.server.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/** Compact JWT helpers, RS256 only: parse, sign, verify a signature, and the short form shown on screen. */
public final class Jwts {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Jwts() {}

    public record Parsed(JsonNode header, JsonNode claims, byte[] signingInput, byte[] signature) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Parsed p
                    && java.util.Objects.equals(header, p.header)
                    && java.util.Objects.equals(claims, p.claims)
                    && java.util.Arrays.equals(signingInput, p.signingInput)
                    && java.util.Arrays.equals(signature, p.signature);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    header, claims, java.util.Arrays.hashCode(signingInput), java.util.Arrays.hashCode(signature));
        }

        @Override
        public String toString() {
            return "Parsed[" + "header=" + header + ", claims=" + claims + ", signature=" + signature.length + " bytes"
                    + "]";
        }
    }

    public static String base64url(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    public static byte[] fromBase64url(String text) {
        return DECODER.decode(text);
    }

    public static Parsed parse(String jwt) {
        if (jwt == null) throw new IllegalArgumentException("not a JWT");
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("not a JWT: " + parts.length + " parts");
        try {
            return new Parsed(
                    JSON.readTree(fromBase64url(parts[0])),
                    JSON.readTree(fromBase64url(parts[1])),
                    (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                    fromBase64url(parts[2]));
        } catch (IOException e) {
            throw new IllegalArgumentException("not a JWT: unreadable part", e);
        }
    }

    public static String sign(JsonNode header, JsonNode claims, PrivateKey key) {
        try {
            String input = base64url(JSON.writeValueAsBytes(header)) + "." + base64url(JSON.writeValueAsBytes(claims));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + base64url(s.sign());
        } catch (JsonProcessingException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the token", e);
        }
    }

    /** True when the RS256 signature of the parsed token matches the key. */
    public static boolean verify(Parsed jwt, PublicKey key) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update(jwt.signingInput());
            return s.verify(jwt.signature());
        } catch (GeneralSecurityException e) {
            // Handled: a malformed signature or key means "not verified"; the caller refuses the token.
            return false;
        }
    }

    /** The signature cut to 8 characters plus "...", the only part of it that may be shown. */
    public static String shortSignature(String jwt) {
        String sig = jwt.substring(jwt.lastIndexOf('.') + 1);
        return sig.substring(0, Math.min(8, sig.length())) + "...";
    }
}
