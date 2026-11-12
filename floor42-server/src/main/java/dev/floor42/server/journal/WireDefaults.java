package dev.floor42.server.journal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.CommitteeEvent;
import dev.floor42.domain.event.DomainEvents;
import dev.floor42.domain.event.SessionEvent;

/**
 * Fields added after 1.7.0 are left out of a row when they hold the value a row written before means (ADR-0009,
 * ADR-0046): such a row stays readable by 1.7.0, which refuses any field it does not know (ADR-0042). A row that needs
 * the new field (a mission's second committee, a ticket's own permission) carries it, and 1.7.0 sets that row aside as
 * written by a newer version instead of misreading it.
 */
final class WireDefaults {

    private WireDefaults() {}

    /** A committee event's number is written from the second committee of a mission on: absent reads as 1. */
    interface CommitteeNumber {
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = FirstCommittee.class)
        Integer committeeNo();
    }

    /** Jackson's value filter: true means "leave it out". */
    static final class FirstCommittee {
        @Override
        public boolean equals(Object value) {
            return value == null || Integer.valueOf(1).equals(value);
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    /** A permission rule's ticket is written only for a ticket's own rule: absent reads as the whole mission. */
    interface RuleTicket {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TicketId ticket();
    }

    /** A session's cap is written only when known (a live session from 1.9.0 on): absent reads as none. */
    interface SessionCap {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Double capUsd();
    }

    /** A session's bound is written only when it ended without a cost: absent reads as none. */
    interface SessionBound {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Double costUsdBound();
    }

    static JsonMapper.Builder apply(JsonMapper.Builder builder) {
        builder.addMixIn(PermissionRule.class, RuleTicket.class);
        builder.addMixIn(SessionEvent.AgentSessionStarted.class, SessionCap.class);
        builder.addMixIn(SessionEvent.AgentSessionEnded.class, SessionBound.class);
        DomainEvents.types().values().stream()
                .filter(CommitteeEvent.class::isAssignableFrom)
                .forEach(type -> builder.addMixIn(type, CommitteeNumber.class));
        return builder;
    }
}
