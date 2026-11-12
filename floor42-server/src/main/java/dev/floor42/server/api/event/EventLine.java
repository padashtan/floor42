package dev.floor42.server.api.event;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DecisionBasis;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.RecordedReason;
import dev.floor42.domain.TicketId;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What one event shows in the event log, whatever the language: the variant of its sentence (null when the sentence
 * never changes), the values the sentence is written with, and the ids the scene reacts to.
 *
 * @param variant appended to the event type to make the line's message code (ADR-0021, lot 3)
 * @param params the values a translated line needs, by name, each written once for every language
 */
public record EventLine(
        String variant, Map<String, String> params, String agent, String ticket, String question, String action) {

    public EventLine {
        params = Collections.unmodifiableMap(new TreeMap<>(params));
    }

    /** A line whose sentence never changes. */
    static Builder line() {
        return new Builder(null);
    }

    /** A line written with one of its sentences. */
    static Builder line(String variant) {
        return new Builder(variant);
    }

    /**
     * The fields that may hold a sentence Floor42 wrote, and how to read it back: the line then also carries
     * {@code <field>Code} and {@code <field>.<name>}, so the screen writes it in its own language (ADR-0021). A text
     * someone typed, or a tool's own words, has no code and is shown as recorded.
     */
    private static final Map<String, Function<String, Optional<Phrase>>> CODED = Map.of(
            "basis", DecisionBasis::read,
            "detail", DecisionBasis::read,
            "reason", RecordedReason::read,
            "description", RecordedReason::read,
            "rule", rule -> DecisionBasis.read(DecisionBasis.RULE_PREFIX + rule));

    /** A value as a line writes it: nothing for null, a list joined with commas, an enum by its name. */
    static String text(Object value) {
        return switch (value) {
            case null -> "";
            case Collection<?> list ->
                String.join(", ", list.stream().map(EventLine::text).toList());
            case Enum<?> en -> en.name();
            default -> value.toString();
        };
    }

    /** Builds a line value by value: every parameter is named here, so renaming an event's field breaks the build. */
    static final class Builder {
        private final String variant;
        private final Map<String, String> params = new TreeMap<>();
        private String agent;
        private String ticket;
        private String question;
        private String action;

        private Builder(String variant) {
            this.variant = variant;
        }

        Builder param(String name, Object value) {
            params.put(name, text(value));
            return this;
        }

        Builder agent(AgentId id) {
            return agent(id == null ? null : id.value());
        }

        Builder agent(String id) {
            this.agent = id;
            return this;
        }

        Builder ticket(TicketId id) {
            this.ticket = id == null ? null : id.value();
            return this;
        }

        Builder question(QuestionId id) {
            this.question = id == null ? null : id.value();
            return this;
        }

        Builder action(ActionId id) {
            this.action = id == null ? null : id.value();
            return this;
        }

        EventLine build() {
            CODED.forEach((field, reader) -> {
                String text = params.get(field);
                if (text == null || text.isEmpty()) return;
                reader.apply(text).ifPresent(p -> {
                    params.put(field + "Code", p.code());
                    p.params().forEach((k, v) -> params.put(field + "." + k, v));
                });
            });
            return new EventLine(variant, params, agent, ticket, question, action);
        }
    }
}
