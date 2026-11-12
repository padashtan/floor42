package dev.floor42.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The rounds of objections of every mission, derived while applying: the cause of an event names its round. */
final class ObjectionRounds {

    static final String CAUSE = "objections-";
    private static final Pattern ROUND = Pattern.compile(CAUSE + "(\\d{1,6})");

    private final Map<MissionId, TreeMap<Integer, ObjectionRound>> rounds = new HashMap<>();

    /** An event of a mission that a round's cause may have recorded: a directive (targeted) or a new ticket. */
    void note(MissionId mission, String cause, TicketId ticket, boolean created) {
        if (cause == null) return;
        Matcher m = ROUND.matcher(cause);
        if (!m.matches()) return;
        int no = Integer.parseInt(m.group(1));
        TreeMap<Integer, ObjectionRound> byNo = rounds.computeIfAbsent(mission, k -> new TreeMap<>());
        ObjectionRound r = byNo.getOrDefault(no, new ObjectionRound(no, List.of(), List.of()));
        byNo.put(no, created ? r.withCreated(ticket) : r.withTargeted(ticket));
    }

    List<ObjectionRound> of(MissionId mission) {
        TreeMap<Integer, ObjectionRound> byNo = rounds.get(mission);
        return byNo == null ? List.of() : List.copyOf(byNo.values());
    }

    /** The number the next round takes. */
    int next(MissionId mission) {
        TreeMap<Integer, ObjectionRound> byNo = rounds.get(mission);
        return byNo == null || byNo.isEmpty() ? 1 : byNo.lastKey() + 1;
    }
}
