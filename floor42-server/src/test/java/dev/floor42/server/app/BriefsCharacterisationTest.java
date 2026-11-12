package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.GoldenJournals;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Retro;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.golden.GoldenFile;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Every text Floor42 composes for an agent or for the director, from the domain's golden journals, compared with what
 * the code produced when the master was recorded ({@code src/test/resources/golden/briefs.txt}; {@code
 * -Dfloor42.golden.record=true} records it again). After each event, the briefs of that event's mission are composed
 * against the company as it stood then; a text seen earlier is written once. The parsers read back every text the
 * journals hold. The end-of-mission report and the pull request text are composed at the end of each journal.
 */
class BriefsCharacterisationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void everyBriefOfTheGoldenJournalsReadsAsRecorded() {
        // The briefs state the host's system: the master reads the same on every machine.
        BriefEnvironment.pinHostForTests("Windows 11");
        OfficeSettings.pinMandatoryForTests(false);
        try {
            everyBrief();
        } finally {
            BriefEnvironment.pinHostForTests(null);
            OfficeSettings.pinMandatoryForTests(null);
        }
    }

    private static void everyBrief() {
        StringBuilder out = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, List<EventEnvelope>> journals = new LinkedHashMap<>(GoldenJournals.all());
        journals.put("variants", BriefVariants.journal());
        journals.forEach((journal, events) -> {
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < events.size(); i++) {
                MissionId mission = events.get(i).mission();
                if (mission == null) continue;
                Company c = Company.replay(events.subList(0, i + 1));
                Map<String, String> texts = new LinkedHashMap<>();
                c.missions().find(mission).ifPresent(m -> briefs(c, m, texts));
                write(lines, seen, "@" + i, texts);
            }
            Company end = Company.replay(events);
            Map<String, String> texts = new LinkedHashMap<>();
            List<MissionReports.ReportOutput> outputs =
                    journal.equals("variants") ? BriefVariants.outputs() : List.of();
            for (Mission m : end.missions().all()) ends(end, m, outputs, texts);
            write(lines, seen, "@end", texts);
            if (!lines.isEmpty())
                out.append("==== ").append(journal).append('\n').append(lines);
        });
        assertThat(GoldenFile.check("briefs.txt", out.toString())).isNull();
    }

    private static void write(StringBuilder lines, Set<String> seen, String at, Map<String, String> texts) {
        texts.forEach((name, text) -> {
            String kind = name.substring(0, name.indexOf(' '));
            if (!seen.add(kind + "\n" + text)) return;
            lines.append("== ").append(at).append(' ').append(name).append('\n');
            lines.append(text.replace("\r", "\\r")).append("\n== end\n");
        });
    }

    private static void briefs(Company c, Mission m, Map<String, String> out) {
        String id = m.id().value();
        put(out, "offeredTools " + id, () -> Briefs.offeredTools(m).toString());
        put(out, "measuredTools " + id, () -> Briefs.measuredTools(c));
        put(out, "retroFacts " + id, () -> Briefs.retroFacts(c, m));
        Optional<Agent> first = c.staff().all().stream().findFirst();
        first.ifPresent(a -> put(out, "planner " + id + " " + a.id(), () -> brief(c, Briefs.planner(c, m, null, a))));
        for (Ticket t : c.tickets().of(m.id())) tickets(c, m, t, out);
        c.committees().latest(m.id()).ifPresent(x -> committee(c, m, x, out));
        c.retros().find(m.id()).ifPresent(r -> retro(c, m, r, out));
        c.staff()
                .staffingReview(m.id())
                .ifPresent(r -> agent(c, r.planner())
                        .ifPresent(a -> put(
                                out,
                                "staffing " + id + " " + a.id(),
                                () -> brief(
                                        c,
                                        Briefs.staffing(
                                                m,
                                                a,
                                                Staffing.facts(c, m.id(), 2).text())))));
    }

    private static void tickets(Company c, Mission m, Ticket t, Map<String, String> out) {
        String id = m.id().value() + " " + t.id().value();
        if (t.assignee() != null) {
            agent(c, t.assignee()).ifPresent(a -> {
                put(out, "producer " + id + " " + a.id(), () -> brief(c, Briefs.producer(c, m, t, a)));
                if (t.kind() == TicketKind.PLANNING)
                    put(out, "planner " + id + " " + a.id(), () -> brief(c, Briefs.planner(c, m, t, a)));
            });
            reviewer(c, t)
                    .ifPresent(a ->
                            put(out, "reviewer " + id + " " + a.id(), () -> brief(c, Briefs.reviewer(c, m, t, a))));
        }
        int n = 0;
        for (Ticket.Consultation h : t.consultations()) {
            n++;
            Agent a = agent(c, h.answeredBy() != null ? h.answeredBy() : h.reviewer())
                    .orElse(null);
            if (a == null) continue;
            put(out, "consultant " + id + " #" + n, () -> brief(c, Briefs.consultant(c, m, t, a, h)));
            put(out, "researcher " + id + " #" + n, () -> brief(c, Briefs.researcher(a, h)));
        }
        if (t.deliverable() != null) parsed(out, id, t.deliverable());
        t.reviews().forEach(r -> parsed(out, id + " review", r.findings()));
    }

    private static void committee(Company c, Mission m, Committee x, Map<String, String> out) {
        String id = m.id().value();
        for (AgentId member : x.memberIds())
            agent(c, member)
                    .ifPresent(a -> put(
                            out,
                            "committeeMember " + id + " " + a.id(),
                            () -> brief(c, Briefs.committeeMember(c, m, x, a))));
        agent(c, x.chair())
                .ifPresent(a -> put(
                        out, "committeeChair " + id + " " + a.id(), () -> brief(c, Briefs.committeeChair(c, m, x, a))));
        if (x.analyst() != null)
            agent(c, x.analyst())
                    .ifPresent(a ->
                            put(out, "analyst " + id + " " + a.id(), () -> brief(c, Briefs.analyst(c, m, x, a))));
        int n = 0;
        for (Committee.Contribution k : x.contributions()) parsed(out, id + " contribution#" + ++n, k.text());
        if (x.clarificationNote() != null) parsed(out, id + " clarification", x.clarificationNote());
    }

    private static void retro(Company c, Mission m, Retro r, Map<String, String> out) {
        String id = m.id().value();
        for (AgentId p : r.participants())
            agent(c, p)
                    .ifPresent(a -> put(
                            out,
                            "retroParticipant " + id + " " + a.id(),
                            () -> brief(c, Briefs.retroParticipant(c, m, r, a))));
        agent(c, r.chair())
                .ifPresent(a ->
                        put(out, "retroChair " + id + " " + a.id(), () -> brief(c, Briefs.retroChair(c, m, r, a))));
        int n = 0;
        for (Retro.Contribution k : r.contributions())
            if (k.text() != null) parsed(out, id + " retro#" + ++n, k.text());
        if (r.text() != null) parsed(out, id + " retroText", r.text());
    }

    /** The texts composed for the director at a mission's end. */
    private static void ends(Company c, Mission m, List<MissionReports.ReportOutput> outputs, Map<String, String> out) {
        String id = m.id().value();
        put(
                out,
                "missionReport " + id,
                () -> MissionReports.compose(c, m, "main", outputs, NOW).toString());
        List<Workspaces.CommitLine> commits = List.of(
                new Workspaces.CommitLine("abc1234", "Add the gateway", "agent", "2026-10-20T10:00:00Z"),
                new Workspaces.CommitLine("def5678", "Write  the\nADR", "agent", "2026-10-20T11:00:00Z"));
        List<String> files = List.of("src/Main.java", "docs/adr/0001-gateway.md", "notes/adr-12 choice.md");
        put(
                out,
                "pullRequest " + id,
                () -> PullRequestText.compose(c, m.id(), null, "feature/x", commits, files)
                        .toString());
        for (var w : c.workshop().workspaces(m.id()))
            put(
                    out,
                    "pullRequest " + id + " " + w.agent(),
                    () -> PullRequestText.compose(c, m.id(), w.agent(), w.branch(), List.of(), List.of())
                            .toString());
    }

    private static void parsed(Map<String, String> out, String where, String text) {
        if (text == null) return;
        put(
                out,
                "parsed " + where,
                () -> String.join(
                        "\n",
                        "summary=" + Briefs.summary(text),
                        "clarifications=" + Briefs.clarifications(text),
                        "plan=" + Briefs.plan(text),
                        "help=" + Briefs.help(text),
                        "retroConclusion=" + Briefs.retroConclusion(text),
                        "wentWell=" + Briefs.retroLines(text, "WENT WELL"),
                        "wentBadly=" + Briefs.retroLines(text, "WENT BADLY"),
                        "staffingAdvice=" + Briefs.staffingAdvice(text),
                        "producerVerdict=" + MissionReports.producerVerdict(text),
                        "firstLine=" + MissionReports.firstLine(text)));
    }

    private static String brief(Company c, String brief) {
        return Briefs.inLanguage(c, brief);
    }

    private static Optional<Agent> reviewer(Company c, Ticket t) {
        if (!t.reviews().isEmpty()) return agent(c, t.reviews().getLast().reviewer());
        return c.staff().all().stream()
                .filter(a -> !a.id().equals(t.assignee()))
                .findFirst();
    }

    private static Optional<Agent> agent(Company c, AgentId id) {
        return id == null ? Optional.empty() : c.staff().find(id);
    }

    private static void put(Map<String, String> out, String name, Supplier<String> text) {
        String value;
        try {
            value = text.get();
        } catch (RuntimeException e) {
            value = "threw " + e.getClass().getName() + ": " + e.getMessage();
        }
        out.put(name, String.valueOf(value));
    }
}
