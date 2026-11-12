package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.CommitteeSeatEvent;
import dev.floor42.domain.event.CommitteeSeatEvent.CommitteeSeatDefined;
import dev.floor42.domain.event.EventEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The committee seats the director defined, in the order they were first defined. */
final class CommitteeSeats {

    private final CompanyView view;
    private final Map<String, CommitteeSeat> committeeSeats = new LinkedHashMap<>();

    CommitteeSeats(CompanyView view) {
        this.view = view;
    }

    List<CommitteeSeat> committeeSeats() {
        return List.copyOf(committeeSeats.values());
    }

    /** Floor42 checks the key is none of its built-in seats; the domain checks it names no other agent. */
    List<EventEnvelope> defineCommitteeSeat(CommandContext ctx, CommitteeSeatDefined seat) {
        requireDirector(ctx);
        String key = seat.key();
        checkWords(seat);
        List<String> chosen = panels(seat.panels());
        Agent existing = view.agent(new AgentId(key)).orElse(null);
        require(
                existing == null || committeeSeats.containsKey(key) && !existing.dismissed,
                Refusal.AGENT_EXISTS,
                key + " already names an agent: choose another name");
        return view.record(ctx, null)
                .emit(new CommitteeSeatDefined(
                        key,
                        seat.name().strip(),
                        seat.title().strip(),
                        seat.model().strip(),
                        seat.effort().strip(),
                        seat.lens().strip(),
                        chosen))
                .events();
    }

    private static void checkWords(CommitteeSeatDefined seat) {
        require(
                seat.key() != null && seat.key().matches("[a-z][a-z0-9-]{1,30}"),
                Refusal.INVALID_ARGUMENT,
                "A key of 2 to 31 letters, " + "digits or dashes, starting with a letter");
        requireText(seat.name(), "name");
        requireText(seat.title(), "title");
        requireText(seat.model(), "model");
        requireText(seat.effort(), "effort");
        requireText(seat.lens(), "lens");
        require(
                seat.lens().strip().length() <= CommitteesFacade.SEAT_LENS_MAX,
                Refusal.INVALID_ARGUMENT,
                "A lens of at most " + CommitteesFacade.SEAT_LENS_MAX + " characters");
    }

    private static List<String> panels(List<String> panels) {
        require(
                panels != null && !panels.isEmpty() && panels.stream().noneMatch(java.util.Objects::isNull),
                Refusal.INVALID_ARGUMENT,
                "Which committees: architecture, idea?");
        List<String> chosen = panels.stream()
                .map(p -> p.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        require(
                chosen.stream().allMatch(p -> p.equals("architecture") || p.equals("idea")),
                Refusal.INVALID_ARGUMENT,
                "Committees are architecture and idea");
        return chosen;
    }

    /** The only way this area's state changes. */
    void apply(CommitteeSeatEvent event, EventEnvelope envelope) {
        switch (event) {
            case CommitteeSeatDefined e -> on(e);
        }
    }

    void on(CommitteeSeatDefined e) {
        committeeSeats.put(
                e.key(),
                new CommitteeSeat(
                        e.key(), e.name(), e.title(), e.model(), e.effort(), e.lens(), List.copyOf(e.panels())));
    }
}
