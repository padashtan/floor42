package dev.floor42.server.api;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.Retro;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read models of end-of-mission retrospectives (ADR-0020) and of the lessons they draw. */
public final class RetroViews {

    private RetroViews() {}

    /** One participant's say in a retrospective; {@code skipped} says why it did not speak. */
    public record RetroLine(
            String agent, List<String> wentWell, List<String> wentBadly, String text, String skipped, String at) {
        public RetroLine {
            wentBadly = wentBadly == null ? null : Collections.unmodifiableList(new ArrayList<>(wentBadly));
            wentWell = wentWell == null ? null : Collections.unmodifiableList(new ArrayList<>(wentWell));
        }

        static RetroLine of(Retro.Contribution x) {
            return new RetroLine(
                    x.agent().value(), x.wentWell(), x.wentBadly(), x.text(), x.skipped(), x.at().toString());
        }
    }

    /** An end-of-mission retrospective as persisted (ADR-0020), with the lessons it proposed. */
    public record RetroView(
            String ticket,
            String chair,
            List<String> participants,
            List<String> pending,
            String facts,
            List<RetroLine> contributions,
            List<String> conclusions,
            String text,
            String reason,
            boolean concluded,
            List<LessonView> lessons) {
        public RetroView {
            conclusions = conclusions == null ? null : Collections.unmodifiableList(new ArrayList<>(conclusions));
            contributions = contributions == null ? null : Collections.unmodifiableList(new ArrayList<>(contributions));
            lessons = lessons == null ? null : Collections.unmodifiableList(new ArrayList<>(lessons));
            participants = participants == null ? null : Collections.unmodifiableList(new ArrayList<>(participants));
            pending = pending == null ? null : Collections.unmodifiableList(new ArrayList<>(pending));
        }

        static RetroView of(Company c, Retro r) {
            return new RetroView(
                    r.ticket().value(),
                    r.chair().value(),
                    r.participants().stream().map(AgentId::value).toList(),
                    r.pending().stream().map(AgentId::value).toList(),
                    r.facts(),
                    r.contributions().stream().map(RetroLine::of).toList(),
                    r.conclusions(),
                    r.text(),
                    r.reason(),
                    r.concluded(),
                    c.retros().lessons().stream()
                            .filter(l -> l.mission().equals(r.mission()))
                            .map(LessonView::of)
                            .toList());
        }
    }

    /** A lesson and where it stands; {@code audience} null means every role. */
    public record LessonView(
            String id,
            String mission,
            String audience,
            String text,
            String status,
            String reason,
            String proposedBy,
            String proposedAt) {
        public static LessonView of(Lesson l) {
            return new LessonView(
                    l.id(),
                    l.mission().value(),
                    l.audience() == null ? null : l.audience().name(),
                    l.text(),
                    l.status().name(),
                    l.reason(),
                    l.proposedBy() == null ? null : l.proposedBy().value(),
                    l.proposedAt().toString());
        }
    }
}
