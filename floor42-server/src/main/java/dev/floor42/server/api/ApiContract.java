package dev.floor42.server.api;

import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

/**
 * What every route shares, added to the OpenAPI document by rule instead of by hand on each entry: the {@code Problem}
 * body and the shared error responses, the three security schemes applied per path group, and the stability marks.
 */
final class ApiContract {

    static final String UNSTABLE = "unstable";

    /** Routes that may change in any release; the rest is the stable core. */
    private static final List<Pattern> UNSTABLE_ROUTES = List.of(
            Pattern.compile("/api/iam/.*"),
            Pattern.compile("/api/calls.*"),
            Pattern.compile("/api/sessions/[^/]+/(context|trust-chain)"),
            Pattern.compile("/api/(demo|terminals|plugins|backups|resume|workflows|lessons).*"),
            Pattern.compile("/api/(sandbox|projects|committee|journal)/.*"),
            Pattern.compile("/api/unsafe-mode"),
            Pattern.compile("/api/agents/dismiss-all-but-core"),
            Pattern.compile("/api/missions/[^/]+/(retro/.*|staffing|held|nudge|uncommitted|graph/extend)"),
            Pattern.compile("/api/missions/[^/]+/replay-export.*"),
            Pattern.compile("/mcp"),
            Pattern.compile("/hooks/.*"));

    private ApiContract() {}

    static boolean isUnstable(String path) {
        return UNSTABLE_ROUTES.stream().anyMatch(p -> p.matcher(path).matches());
    }

    static void apply(OpenAPI openAPI) {
        Components components = openAPI.getComponents();
        if (components == null) openAPI.setComponents(components = OASFactory.createComponents());
        components.addSchema(
                "Problem",
                OASFactory.createSchema()
                        .addType(Schema.SchemaType.OBJECT)
                        .description("Every error body: a stable code and a readable message, never a stack trace.")
                        .addRequired("code")
                        .addRequired("message")
                        .addProperty("code", OASFactory.createSchema().addType(Schema.SchemaType.STRING))
                        .addProperty("message", OASFactory.createSchema().addType(Schema.SchemaType.STRING)));
        problems(components);
        securitySchemes(components);
        if (openAPI.getPaths() == null || openAPI.getPaths().getPathItems() == null) return;
        openAPI.getPaths()
                .getPathItems()
                .forEach((path, item) -> item.getOperations().forEach((method, op) -> operation(path, method, op)));
        stabilityOfTags(openAPI);
    }

    private static void problems(Components components) {
        problem(components, "BadRequest", "The request itself is wrong (INVALID_ARGUMENT, MISSING_..., BAD_REQUEST).");
        problem(components, "Unauthorized", "No valid credential (UNAUTHORIZED).");
        problem(
                components,
                "Forbidden",
                "Another host or origin, a missing X-Floor42-Request header (FORBIDDEN), a token whose scope does not"
                        + " allow the request (SCOPE), or an agent credential on the director's API.");
        problem(components, "NotFound", "What the request names does not exist (NOT_FOUND, UNKNOWN_...).");
        problem(components, "Conflict", "The state of things refuses the request: a business rule.");
        problem(components, "UnsupportedMedia", "The body is not JSON (Content-Type: application/json).");
        problem(components, "TooLarge", "The body is too large (FILE_TOO_LARGE).");
        problem(
                components,
                "Unavailable",
                "The event journal cannot be written (JOURNAL_UNAVAILABLE, JOURNAL_LOCKED).");
    }

    private static void securitySchemes(Components components) {
        components.addSecurityScheme(
                "cookieAuth",
                OASFactory.createSecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("floor42_session")
                        .description(
                                "The browser's sign-in cookie. Every request that changes something also needs"
                                        + " the header `X-Floor42-Request: 1` and an Origin that is exactly the daemon's own."));
        components.addSecurityScheme(
                "bearerToken",
                OASFactory.createSecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("A script token issued from the desk (System > Sessions and tokens), scope `read`,"
                                + " `missions:write` or `all`. `missions:write` may only change what the"
                                + " MISSION_WRITES allow-list names: create a mission, approve or reject it and its"
                                + " tickets, nudge it, send a workshop directive, answer a non-permission question."));
        components.addSecurityScheme(
                "agentBearer",
                OASFactory.createSecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("An agent session's hook credential: opens /hooks/* and /mcp only, refused"
                                + " (403) everywhere else."));
    }

    private static void problem(Components components, String name, String description) {
        components.addResponse(
                name,
                OASFactory.createAPIResponse()
                        .description(description)
                        .content(OASFactory.createContent()
                                .addMediaType(
                                        "application/json",
                                        OASFactory.createMediaType()
                                                .schema(OASFactory.createSchema()
                                                        .ref("#/components/schemas/Problem")))));
    }

    private static void operation(String path, PathItem.HttpMethod method, Operation op) {
        boolean agent = path.equals("/mcp") || path.startsWith("/hooks/");
        boolean write = method != PathItem.HttpMethod.GET && method != PathItem.HttpMethod.HEAD;
        if (agent) {
            op.setSecurity(List.of(OASFactory.createSecurityRequirement().addScheme("agentBearer")));
            responses(op, "401", "Unauthorized", "403", "Forbidden");
        } else if (!path.equals("/api/status")) {
            op.setSecurity(List.of(
                    OASFactory.createSecurityRequirement().addScheme("cookieAuth"),
                    OASFactory.createSecurityRequirement().addScheme("bearerToken")));
            responses(op, "401", "Unauthorized", "403", "Forbidden");
            // A name that is not there is a 404 whatever the route (a mission, a folder, a session id in a query).
            responses(op, "404", "NotFound", "409", "Conflict");
            if (write) responses(op, "503", "Unavailable");
        }
        boolean body = op.getRequestBody() != null;
        if (body || (op.getParameters() != null && !op.getParameters().isEmpty())) responses(op, "400", "BadRequest");
        if (body) responses(op, "413", "TooLarge", "415", "UnsupportedMedia");
        if (isUnstable(path)) op.addExtension("x-stability", UNSTABLE);
    }

    /** Pairs of status and component name; replaces a bare response of the same status. */
    private static void responses(Operation op, String... pairs) {
        if (op.getResponses() == null) op.setResponses(OASFactory.createAPIResponses());
        for (int i = 0; i < pairs.length; i += 2) {
            op.getResponses()
                    .addAPIResponse(pairs[i], OASFactory.createAPIResponse().ref(pairs[i + 1]));
        }
    }

    /** A group with an unstable route says so in its own description. */
    private static void stabilityOfTags(OpenAPI openAPI) {
        if (openAPI.getTags() == null) return;
        openAPI.getTags().forEach(tag -> {
            long all = 0;
            long unstable = 0;
            for (var e : openAPI.getPaths().getPathItems().entrySet()) {
                for (Operation op : e.getValue().getOperations().values()) {
                    if (op.getTags() == null || !op.getTags().contains(tag.getName())) continue;
                    all++;
                    if (isUnstable(e.getKey())) unstable++;
                }
            }
            if (unstable == 0) return;
            tag.setDescription(tag.getDescription()
                    + (unstable == all
                            ? " Unstable: this whole group may change in any release (x-stability: unstable)."
                            : " Operations marked x-stability: unstable in this group may change in any release."));
        });
    }
}
