package dev.floor42.server.iam.fga;

import dev.floor42.server.iam.SecretVault;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where OpenFGA is: {@code floor42.iam.fga.url} (the compose stack publishes http://127.0.0.1:8181) and its preshared
 * key, from the vault entry {@code fga-key} or, when set, {@code floor42.iam.fga.key} (tests, scripts). One store per
 * data folder, named {@code floor42}.
 * <p>shortcut: one store and one model line, no multi-tenant; upgrade when two offices share one OpenFGA.
 * {@code floor42.iam.fga.required=false} exists for the tests of IAM mode that run without an OpenFGA; it is on by
 * default and nothing in the product turns it off.
 */
@ApplicationScoped
public class FgaSettings {

    public static final String STORE_NAME = "floor42";
    public static final String KEY_ENTRY = "fga-key";

    private final Optional<String> url;
    private final Optional<String> key;
    private final boolean required;
    private final SecretVault vault;

    @Inject
    FgaSettings(
            @ConfigProperty(name = "floor42.iam.fga.url") Optional<String> url,
            @ConfigProperty(name = "floor42.iam.fga.key") Optional<String> key,
            @ConfigProperty(name = "floor42.iam.fga.required", defaultValue = "true") boolean required,
            SecretVault vault) {
        this.url = url.filter(s -> !s.isBlank());
        this.key = key.filter(s -> !s.isBlank());
        this.required = required;
        this.vault = vault;
    }

    public Optional<URI> url() {
        return url.map(URI::create);
    }

    /** IAM mode refuses to start without OpenFGA settings. */
    public boolean required() {
        return required;
    }

    /** Read at each call, so a key entered while Floor42 runs is used at once. Never logged. */
    public Optional<String> key() {
        if (key.isPresent()) return key;
        return vault.get(KEY_ENTRY).map(b -> new String(b, StandardCharsets.UTF_8).strip());
    }
}
