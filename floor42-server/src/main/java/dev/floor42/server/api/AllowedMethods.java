package dev.floor42.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.smallrye.openapi.runtime.OpenApiDocumentService;
import io.smallrye.openapi.runtime.io.Format;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The methods a path serves, read from the OpenAPI document the daemon serves: what a 405 names in its {@code Allow}
 * header (RFC 9110 requires one).
 */
@ApplicationScoped
class AllowedMethods {

    private record Route(Pattern path, String methods) {}

    private final OpenApiDocumentService documents;

    @Inject
    AllowedMethods(OpenApiDocumentService documents) {
        this.documents = documents;
    }

    private volatile List<Route> routes;

    /** The {@code Allow} value for a path, or null when the path is no documented route. */
    String allow(String path) {
        for (Route r : routes()) if (r.path().matcher(path).matches()) return r.methods();
        return null;
    }

    private List<Route> routes() {
        List<Route> r = routes;
        if (r != null) return r;
        List<Route> built = new ArrayList<>();
        try {
            JsonNode paths = new ObjectMapper()
                    .readTree(documents.getDocument("<default>", Format.JSON))
                    .path("paths");
            paths.properties().forEach(e -> {
                TreeSet<String> methods = new TreeSet<>();
                e.getValue().fieldNames().forEachRemaining(m -> methods.add(m.toUpperCase(Locale.ROOT)));
                if (methods.contains("GET")) methods.add("HEAD");
                methods.add("OPTIONS");
                String regex = java.util.Arrays.stream(e.getKey().split("\\{[^}]+}", -1))
                        .map(Pattern::quote)
                        .collect(java.util.stream.Collectors.joining("[^/]+"));
                built.add(new Route(Pattern.compile(regex), String.join(", ", methods)));
            });
        } catch (IOException | RuntimeException e) {
            // Handled: no document, no Allow; the 405 stays what it was, without the header.
        }
        return routes = List.copyOf(built);
    }
}
