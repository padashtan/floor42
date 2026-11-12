package dev.floor42.server.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.security.OwnerOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The external resources the broker may call, and which agents may use them: two files under {@code <data-dir>/iam/}
 * ({@code resources.json}, {@code grants.json}), the director's to edit; not journaled (v1.0.0). A call also needs the
 * mission's delegation to name the resource. An unreadable file reads as empty: nothing is callable (fail closed).
 */
@ApplicationScoped
public class ResourceCatalogue {

    static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,40}");
    static final int MAX_RESPONSE = 1 << 20;
    private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** {@code local}: a loopback test resource, the only kind that may use plain http and a loopback address. */
    public record Resource(
            String name,
            URI baseUrl,
            String audience,
            List<String> scopes,
            List<String> methods,
            List<String> pathPrefixes,
            int maxResponseBytes,
            boolean local) {
        public Resource {
            methods = methods == null ? null : Collections.unmodifiableList(new ArrayList<>(methods));
            pathPrefixes = pathPrefixes == null ? null : Collections.unmodifiableList(new ArrayList<>(pathPrefixes));
            scopes = scopes == null ? null : Collections.unmodifiableList(new ArrayList<>(scopes));
        }
    }

    private final Path resources;
    private final Path grants;
    private final ObjectMapper json;
    private final Delegations delegations;

    @Inject
    ResourceCatalogue(
            @ConfigProperty(name = "floor42.data-dir") Path dataDir, ObjectMapper json, Delegations delegations) {
        this.resources = dataDir.resolve("iam").resolve("resources.json");
        this.grants = dataDir.resolve("iam").resolve("grants.json");
        this.json = json;
        this.delegations = delegations;
    }

    public synchronized List<Resource> all() {
        return read(resources, new TypeReference<List<Resource>>() {}, List.of());
    }

    public Optional<Resource> find(String name) {
        return all().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /** Adds or replaces a resource; {@code IllegalArgumentException} says what is wrong with it. */
    public synchronized void put(Resource r) {
        Resource clean = checked(r);
        List<Resource> next = new ArrayList<>(all());
        next.removeIf(x -> x.name().equals(clean.name()));
        next.add(clean);
        write(resources, next);
    }

    public synchronized void remove(String name) {
        List<Resource> next = new ArrayList<>(all());
        next.removeIf(x -> x.name().equals(name));
        write(resources, next);
        Map<String, List<String>> kept = new LinkedHashMap<>();
        grantsOf()
                .forEach((agent, names) -> kept.put(
                        agent, names.stream().filter(n -> !n.equals(name)).toList()));
        write(grants, kept);
    }

    public synchronized void grant(AgentId a, List<String> names) {
        Map<String, List<String>> all = new LinkedHashMap<>(grantsOf());
        all.put(a.value(), List.copyOf(names));
        write(grants, all);
    }

    public synchronized Map<String, List<String>> grantsOf() {
        return read(grants, new TypeReference<Map<String, List<String>>>() {}, Map.of());
    }

    /** The agent's grant AND the mission's delegation both name the resource. */
    public boolean allowed(AgentId a, MissionId m, String name) {
        return grantsOf().getOrDefault(a.value(), List.of()).contains(name)
                && delegations.find(m).filter(d -> !d.revoked()).stream()
                        .anyMatch(d -> d.resources().contains(name));
    }

    private static Resource checked(Resource r) {
        if (r == null || r.name() == null || !NAME.matcher(r.name()).matches())
            throw new IllegalArgumentException("name must match [a-z0-9-]{1,40}");
        if (r.baseUrl() == null || r.baseUrl().getHost() == null || r.baseUrl().getRawUserInfo() != null)
            throw new IllegalArgumentException("baseUrl must be an absolute address without credentials");
        origin(r);
        noNulls(r);
        if (r.audience() == null || r.audience().isBlank()) throw new IllegalArgumentException("audience is required");
        List<String> verbs = r.methods() == null
                ? List.of()
                : r.methods().stream()
                        .map(s -> s.strip().toUpperCase(Locale.ROOT))
                        .toList();
        if (verbs.isEmpty() || !VERBS.containsAll(verbs))
            throw new IllegalArgumentException("methods must be among " + VERBS);
        if (r.pathPrefixes() == null
                || r.pathPrefixes().isEmpty()
                || r.pathPrefixes().stream().anyMatch(p -> !p.startsWith("/")))
            throw new IllegalArgumentException("path prefixes are required and start with /");
        int cap = r.maxResponseBytes() <= 0 ? MAX_RESPONSE : Math.min(r.maxResponseBytes(), MAX_RESPONSE);
        return new Resource(
                r.name(),
                r.baseUrl(),
                r.audience().strip(),
                r.scopes() == null ? List.of() : List.copyOf(r.scopes()),
                verbs,
                List.copyOf(r.pathPrefixes()),
                cap,
                r.local());
    }

    private static void noNulls(Resource r) {
        for (List<String> list : java.util.Arrays.asList(r.methods(), r.pathPrefixes(), r.scopes())) {
            if (list != null && list.stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("methods, pathPrefixes and scopes hold no null");
        }
    }

    /** https, or http for a local resource; an origin only, the path belongs in pathPrefixes. */
    private static void origin(Resource r) {
        String scheme =
                r.baseUrl().getScheme() == null ? "" : r.baseUrl().getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && r.local()))
            throw new IllegalArgumentException("baseUrl is https, or http for a local resource");
        String basePath = r.baseUrl().getRawPath();
        if ((basePath != null && !basePath.isEmpty() && !basePath.equals("/"))
                || r.baseUrl().getRawQuery() != null)
            throw new IllegalArgumentException(
                    "baseUrl is an origin (scheme, host, port): put the path in pathPrefixes");
    }

    private <T> T read(Path file, TypeReference<T> type, T empty) {
        try {
            return Files.exists(file) ? json.readValue(file.toFile(), type) : empty;
        } catch (IOException | RuntimeException e) {
            // Handled: a damaged file reads as empty, so nothing is callable
            return empty;
        }
    }

    private void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            OwnerOnly.restrict(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            OwnerOnly.restrict(tmp);
            Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
