package dev.floor42.server.api.event;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.Company;
import dev.floor42.domain.TicketId;
import java.util.Locale;

/** What a line reads from the company rather than from its event, and the few values every language writes alike. */
final class Lookups {

    private Lookups() {}

    /** Who works the ticket now; null when nobody does or the ticket is unknown. */
    static String assignee(Company c, TicketId ticket) {
        return c.tickets()
                .find(ticket)
                .map(t -> t.assignee() == null ? null : t.assignee().value())
                .orElse(null);
    }

    /** The call an action is ({@code agent tool 'summary'}), or its id when the company does not know it. */
    static String call(Company c, ActionId id) {
        return c.actions()
                .find(id)
                .map(a -> a.agent() + " " + a.tool() + " '" + a.summary() + "'")
                .orElse(id.value());
    }

    /** The first characters of a session id or a commit, as lines show them. */
    static String shortId(String id) {
        return id.substring(0, Math.min(8, id.length()));
    }

    /** An amount in USD, four decimals, the same in every language. */
    static String usd(double amount) {
        return String.format(Locale.ROOT, "%.4f", amount);
    }

    /** An enum's name as a few words: {@code PARALLEL_SESSIONS} is "parallel sessions". */
    static String words(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
