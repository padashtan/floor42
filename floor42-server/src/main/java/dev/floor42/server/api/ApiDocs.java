package dev.floor42.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;

/**
 * The API's documentation for Swagger: a tag, a summary, a description and a request example per operation, kept in
 * {@code api-docs.json} next to the code rather than in annotations spread over the resources. {@code ApiDocsTest}
 * fails when a route has no entry or an entry names no route.
 */
final class ApiDocs {

    static final String RESOURCE = "/api-docs.json";

    private ApiDocs() {}

    static JsonNode load() {
        try (InputStream in = ApiDocs.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing " + RESOURCE);
            // Not Quarkus's mapper: this runs in the OpenAPI filter, at build time, outside CDI; it only reads a tree.
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void apply(OpenAPI openAPI) {
        JsonNode docs = load();
        ObjectMapper json = new ObjectMapper(); // the OpenAPI filter runs outside CDI (see load)
        if (openAPI.getInfo() != null)
            openAPI.getInfo().setDescription(docs.path("info").asText());
        docs.path("tags")
                .properties()
                .forEach(t -> openAPI.addTag(OASFactory.createTag()
                        .name(t.getKey())
                        .description(t.getValue().asText())));
        nullable(openAPI, docs.path("nullable"));
        if (openAPI.getPaths() == null || openAPI.getPaths().getPathItems() == null) return;
        openAPI.getPaths()
                .getPathItems()
                .forEach((path, item) -> item.getOperations().forEach((method, op) -> {
                    JsonNode doc = docs.path("operations").path(method.name() + " " + path);
                    if (doc.isMissingNode()) return;
                    document(op, doc, json);
                }));
    }

    /**
     * The views serialise a field with nothing to say as {@code null}, never leaving it out: every optional property
     * (those not in {@code required}) may be null, except a list, which is never null. A plain number
     * the view leaves null reads as a number: the explicit list in {@code api-docs.json} adds the rest.
     */
    private static void nullable(OpenAPI openAPI, JsonNode nullable) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) return;
        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
        schemas.values().forEach(schema -> {
            if (schema.getProperties() == null) return;
            List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();
            new java.util.ArrayList<>(schema.getProperties().entrySet()).forEach(e -> {
                if (!required.contains(e.getKey())) mayBeNull(schema, e.getKey(), e.getValue());
            });
        });
        nullable.properties().forEach(e -> {
            Schema schema = schemas.get(e.getKey());
            if (schema == null || schema.getProperties() == null) return;
            for (JsonNode name : e.getValue()) {
                Schema property = schema.getProperties().get(name.asText());
                if (property != null) addNull(property);
            }
        });
    }

    private static void mayBeNull(Schema owner, String name, Schema property) {
        if (property.getRef() != null) {
            owner.addProperty(
                    name,
                    OASFactory.createSchema()
                            .addAnyOf(property)
                            .addAnyOf(OASFactory.createSchema().addType(Schema.SchemaType.NULL)));
            return;
        }
        List<Schema.SchemaType> types = property.getType();
        if (types == null || types.contains(Schema.SchemaType.ARRAY)) return;
        addNull(property);
    }

    private static void addNull(Schema property) {
        if (property.getType() == null || !property.getType().contains(Schema.SchemaType.NULL))
            property.addType(Schema.SchemaType.NULL);
    }

    private static void document(Operation op, JsonNode doc, ObjectMapper json) {
        op.setTags(List.of(doc.path("tag").asText()));
        op.setSummary(doc.path("summary").asText());
        op.setDescription(doc.path("description").asText());
        statuses(op, doc);
        if (!doc.has("example")
                || op.getRequestBody() == null
                || op.getRequestBody().getContent() == null) return;
        Map<String, MediaType> types = op.getRequestBody().getContent().getMediaTypes();
        if (types == null) return;
        Object example = json.convertValue(doc.path("example"), Object.class);
        for (MediaType type : types.values()) {
            type.setExample(example);
            // A body read as a raw JSON tree is shown as what it is, an object, not as Jackson's JsonNode.
            if (doc.path("schema").asText().equals("object")) {
                type.setSchema(OASFactory.createSchema().addType(Schema.SchemaType.OBJECT));
            }
        }
    }

    /** {@code responses}: statuses the route answers besides 200 (the 200's content is reused); {@code without}: 200s it never answers. */
    private static void statuses(Operation op, JsonNode doc) {
        if (op.getResponses() == null) return;
        var ok = op.getResponses().getAPIResponse("200");
        doc.path("responses").properties().forEach(r -> {
            var added = OASFactory.createAPIResponse().description(r.getValue().asText());
            if (ok != null && ok.getContent() != null && !r.getKey().equals("204")) added.setContent(ok.getContent());
            op.getResponses().addAPIResponse(r.getKey(), added);
        });
        doc.path("without").forEach(c -> op.getResponses().removeAPIResponse(c.asText()));
    }
}
