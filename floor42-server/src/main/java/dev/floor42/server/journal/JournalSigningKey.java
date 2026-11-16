package dev.floor42.server.journal;

import dev.floor42.server.security.OwnerOnly;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The Ed25519 key (JDK) that signs the journal's checkpoints (ADR-0079): a file of the data folder, owner-only, two
 * base64 lines (PKCS#8 private key, X.509 public key). A file that exists and cannot be read is an error, never a reason
 * to generate another key: that would leave every earlier checkpoint unverifiable.
 */
final class JournalSigningKey {

    static final String FILE = "journal-signing.key";

    private final PrivateKey priv;
    private final PublicKey pub;
    private final String keyId;

    private JournalSigningKey(PrivateKey priv, PublicKey pub) {
        this.priv = priv;
        this.pub = pub;
        this.keyId = HexFormat.of()
                .formatHex(JournalChain.sha256().digest(pub.getEncoded()))
                .substring(0, 16);
    }

    /** Whether the key file is there: a key that is missing beside checkpoints is never replaced silently. */
    static boolean exists(Path dataDir) {
        return Files.exists(dataDir.resolve(FILE), LinkOption.NOFOLLOW_LINKS);
    }

    static JournalSigningKey loadOrCreate(Path dataDir) throws IOException, GeneralSecurityException {
        Path f = dataDir.resolve(FILE);
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) return create(f);
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException(FILE + " is not a plain file");
        String[] lines = Files.readString(f, StandardCharsets.UTF_8).strip().split("\\R");
        if (lines.length != 2) throw new IOException(FILE + " is malformed");
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        Base64.Decoder d = Base64.getDecoder();
        return new JournalSigningKey(
                kf.generatePrivate(new PKCS8EncodedKeySpec(d.decode(lines[0].strip()))),
                kf.generatePublic(new X509EncodedKeySpec(d.decode(lines[1].strip()))));
    }

    /** Written to a partial file made owner-only first, then moved: the key is never readable by others, never half. */
    private static JournalSigningKey create(Path f) throws IOException, GeneralSecurityException {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path partial = f.resolveSibling(FILE + ".partial");
        Files.deleteIfExists(partial);
        Files.createFile(partial);
        OwnerOnly.restrict(partial);
        Base64.Encoder e = Base64.getEncoder();
        Files.writeString(
                partial,
                e.encodeToString(kp.getPrivate().getEncoded()) + "\n"
                        + e.encodeToString(kp.getPublic().getEncoded()) + "\n",
                StandardCharsets.UTF_8);
        Files.move(partial, f, StandardCopyOption.ATOMIC_MOVE);
        return new JournalSigningKey(kp.getPrivate(), kp.getPublic());
    }

    String keyId() {
        return keyId;
    }

    String sign(byte[] message) throws GeneralSecurityException {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(priv);
        s.update(message);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    boolean verify(byte[] message, String signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(message);
            return s.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Handled: a signature that cannot even be parsed is an invalid one
            return false;
        }
    }
}
