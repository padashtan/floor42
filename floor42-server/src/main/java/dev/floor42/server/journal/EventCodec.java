package dev.floor42.server.journal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Identifier;
import dev.floor42.domain.IncidentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.DomainEvents;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stable JSON form of domain events, owned by the journal (not by the REST layer, whose format may evolve freely).
 * Event types are discovered from the sealed hierarchy, so a new event cannot be forgotten here, and checked
 * against the committed list of {@code event-types.txt}.
 */
public final class EventCodec {

    private final ObjectMapper json;
    private final Map<String, Class<? extends DomainEvent>> types;

    public EventCodec() {
        SimpleModule ids = new SimpleModule("floor42-identifiers");
        id(ids, MissionId.class, MissionId::new);
        id(ids, TicketId.class, TicketId::new);
        id(ids, AgentId.class, AgentId::new);
        id(ids, ActionId.class, ActionId::new);
        id(ids, QuestionId.class, QuestionId::new);
        id(ids, IncidentId.class, IncidentId::new);
        json = WireDefaults.apply(JsonMapper.builder())
                .addModule(ids)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        types = DomainEvents.types();
        checkTypes(types.keySet(), listedTypes());
    }

    /** The event types this version reads and writes, as committed: {@code event-types.txt}, one simple name a line. */
    static final String LISTED_TYPES = "event-types.txt";

    /**
     * A type found in the code but not listed, or listed but no longer found, stops the codec: a type lost by a change
     * of the hierarchy would read every journal row of it as written by a newer version, and the company would start
     * without it. A new type is added to the list, a removed one is never removed from the code.
     */
    static void checkTypes(Set<String> discovered, Set<String> listed) {
        if (discovered.equals(listed)) return;
        Set<String> unlisted = new TreeSet<>(discovered);
        unlisted.removeAll(listed);
        Set<String> missing = new TreeSet<>(listed);
        missing.removeAll(discovered);
        throw new JournalException(
                "The event types differ from " + LISTED_TYPES + ": not listed " + unlisted + ", not found " + missing,
                null);
    }

    static Set<String> listedTypes() {
        try (InputStream in = EventCodec.class.getResourceAsStream(LISTED_TYPES)) {
            if (in == null) throw new JournalException("No " + LISTED_TYPES + " beside the codec", null);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new JournalException("Cannot read " + LISTED_TYPES, e);
        }
    }

    public String encode(DomainEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new JournalException("Cannot encode " + event.getClass().getSimpleName(), e);
        }
    }

    /**
     * @throws UnreadableEventException when this version cannot read the row: {@link Unreadable.Reason#NEWER_VERSION}
     *     for an event type, a field or an enumerated value it does not know (what a later Floor42 adds), else
     *     {@link Unreadable.Reason#CORRUPT}. The message names fields, never stored values.
     */
    public DomainEvent decode(String type, String payload) {
        Class<? extends DomainEvent> target = types.get(type);
        if (target == null) {
            throw new UnreadableEventException(
                    Unreadable.Reason.NEWER_VERSION, String.valueOf(type), "unknown event type", null);
        }
        if (payload == null) {
            throw new UnreadableEventException(Unreadable.Reason.CORRUPT, type, "no payload", null);
        }
        try {
            if (!payload.contains(LEGACY_CATEGORY_VALUE)) return json.readValue(payload, target);
            JsonNode tree = json.readTree(payload);
            renameLegacyCategory(tree);
            return json.treeToValue(tree, target);
        } catch (UnrecognizedPropertyException e) {
            throw new UnreadableEventException(Unreadable.Reason.NEWER_VERSION, type, "unknown field " + path(e), e);
        } catch (InvalidFormatException e) {
            if (e.getTargetType() != null && e.getTargetType().isEnum()) {
                throw new UnreadableEventException(
                        Unreadable.Reason.NEWER_VERSION, type, "unknown value of " + path(e), e);
            }
            throw new UnreadableEventException(Unreadable.Reason.CORRUPT, type, "malformed " + path(e), e);
        } catch (JsonMappingException e) {
            throw new UnreadableEventException(
                    Unreadable.Reason.CORRUPT, type, e.getClass().getSimpleName() + " at " + path(e), e);
        } catch (IOException e) {
            // A parse error's message quotes the text it met: only its kind is kept.
            throw new UnreadableEventException(Unreadable.Reason.CORRUPT, type, "not valid JSON", e);
        } catch (RuntimeException e) {
            throw new UnreadableEventException(
                    Unreadable.Reason.CORRUPT,
                    type,
                    "rejected by the event: " + e.getClass().getSimpleName(),
                    e);
        }
    }

    /** The field path of a mapping error, names and indexes only. */
    private static String path(JsonMappingException e) {
        String p = e.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining("."));
        return p.isEmpty() ? "(root)" : p;
    }

    /**
     * Event types are stored by simple name and never held the product's name. One stored value did: the action
     * category of Floor42's own tools was OFFICE before 1.5.0. It is read as FLOOR42, forever; FLOOR42 is written.
     */
    private static final String LEGACY_CATEGORY = "OFFICE";

    private static final String LEGACY_CATEGORY_VALUE = "\"" + LEGACY_CATEGORY + "\"";

    private static void renameLegacyCategory(JsonNode node) {
        if (node instanceof ObjectNode o) {
            JsonNode category = o.get("category");
            if (category != null && category.isTextual() && category.asText().equals(LEGACY_CATEGORY)) {
                o.put("category", ActionCategory.FLOOR42.name());
            }
        }
        if (node.isContainerNode()) node.forEach(EventCodec::renameLegacyCategory);
    }

    /** Identifiers are written as plain strings: {"mission":"M-1"} rather than {"mission":{"value":"M-1"}}. */
    private static <T extends Identifier> void id(SimpleModule module, Class<T> type, Function<String, T> factory) {
        module.addSerializer(type, new StdSerializer<>(type) {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(value.value());
            }
        });
        module.addDeserializer(type, new StdDeserializer<>(type) {
            @Override
            public T deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
                return factory.apply(parser.getValueAsString());
            }
        });
    }
}
