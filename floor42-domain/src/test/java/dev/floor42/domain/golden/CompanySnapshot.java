package dev.floor42.domain.golden;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.Setting;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.Workspace;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The whole observable state of a company, as text: what every public query answers, and every field the company keeps,
 * wherever it keeps it. Two companies with the same snapshot behave the same for every query and every later command.
 *
 * <p>Fields are listed by name, the company's own and those of the package-private parts it is made of, flattened: a
 * field that moves from the company into one of its parts keeps its line.
 */
public final class CompanySnapshot {

    /** A fixed moment for the queries that take one: snapshots must not depend on the clock. */
    static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private static final List<String> TOOLS = List.of("Bash", "Read", "Write", "Edit", "Glob", "Grep", "WebFetch");

    private final Company company;
    private final StringBuilder out = new StringBuilder();

    private CompanySnapshot(Company company) {
        this.company = company;
    }

    public static String of(Company company) {
        CompanySnapshot s = new CompanySnapshot(company);
        s.fields();
        s.queries();
        return s.out.toString();
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------------------------------------------

    private void fields() {
        Map<String, String> fields = new TreeMap<>();
        flatten(company, fields);
        out.append("# fields\n");
        fields.forEach(
                (name, value) -> out.append(name).append(" = ").append(value).append('\n'));
    }

    /** Parts already flattened: one part may be reached from several others (a policy shared by two areas). */
    private final Set<Object> flattened = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private void flatten(Object owner, Map<String, String> into) {
        if (!flattened.add(owner)) return;
        for (Field f : Canonical.fields(owner.getClass())) {
            Object value = Canonical.read(f, owner);
            if (value == company) continue;
            if (isPart(value) || isFacade(value)) {
                flatten(value, into);
                continue;
            }
            if (value != null
                    && (value.getClass().isHidden() || value.getClass().isSynthetic())) continue;
            String previous = into.put(f.getName(), Canonical.of(value));
            if (previous != null) throw new IllegalStateException("Two fields named " + f.getName());
        }
    }

    /**
     * A public area facade of the company ({@code company.missions()}...): it keeps no state of its own, only the parts
     * it reaches, so it is walked through like a part and adds no line.
     */
    private static boolean isFacade(Object value) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        return type.getPackageName().equals("dev.floor42.domain")
                && Modifier.isFinal(type.getModifiers())
                && type.getSimpleName().endsWith("Facade");
    }

    /** A part of the company: a package-private class of the domain that is neither a value nor a container. */
    private static boolean isPart(Object value) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        return type.getPackageName().equals("dev.floor42.domain")
                && !Modifier.isPublic(type.getModifiers())
                && !type.isRecord()
                && !type.isEnum()
                && !(value instanceof Collection<?>)
                && !(value instanceof Map<?, ?>);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------------------------

    private void queries() {
        out.append("# queries\n");
        line("version", company::version);
        line("agents", company.staff()::all);
        line("missions", company.missions()::all);
        line("openQuestions", company.desk()::open);
        line("committeeSeats", company.committees()::seats);
        line("sandboxTools", company.preferences()::sandboxTools);
        line("openRetros", company.retros()::openRetros);
        line("lessons", company.retros()::lessons);
        line("notes", company.notebook()::notes);
        line("openTerminals", company.terminals()::openTerminals);
        line("planUsage", company.verification()::planUsage);
        line("incidents", company.actions()::incidents);
        for (Setting s : Setting.values())
            line("setting " + s, () -> company.preferences().setting(s));
        for (String tool : TOOLS)
            line("defaultPermission " + tool, () -> company.preferences().defaultPermission(tool));
        for (WorkRole role : WorkRole.values())
            line("lessonsFor " + role, () -> company.retros().lessonsFor(role));
        company.retros().lessons().stream()
                .map(Lesson::id)
                .forEach(id -> line("lesson " + id, () -> company.retros().lesson(id)));
        company.staff().all().forEach(this::agent);
        company.missions().all().forEach(this::mission);
        models().forEach(model ->
                line("planLimitRisk " + model, () -> company.verification().planLimitRisk(model, 0.5, NOW)));
        projects().forEach(this::project);
    }

    private void agent(Agent a) {
        String k = " " + a.id().value();
        line("agent" + k, () -> company.staff().find(a.id()));
        line("statusOf" + k, () -> company.queries().statusOf(a.id()));
        line("atWork" + k, () -> company.queries().atWork(a.id()));
        line("handedBackOnDismissal" + k, () -> company.queries().handedBackOnDismissal(a.id()));
    }

    private void mission(Mission m) {
        MissionId id = m.id();
        String k = " " + id.value();
        line("mission" + k, () -> company.missions().find(id));
        line("tickets" + k, () -> company.tickets().of(id));
        line("actions" + k, () -> company.actions().of(id));
        line("questions" + k, () -> company.desk().of(id));
        line("sessions" + k, () -> company.sessions().of(id));
        line("workspaces" + k, () -> company.workshop().workspaces(id));
        line("interventions" + k, () -> company.workshop().interventions(id));
        line("committee" + k, () -> company.committees().latest(id));
        line("committees" + k, () -> company.committees().of(id));
        line("directorFiles" + k, () -> company.workshop().directorFiles(id));
        line("pullRequests" + k, () -> company.deliveries().pullRequests(id));
        line("missionAssets" + k, () -> company.deliveries().missionAssets(id));
        line("retro" + k, () -> company.retros().find(id));
        line("checkRuns" + k, () -> company.verification().checkRuns(id));
        line("spentUsdEstimated" + k, () -> company.verification().spentUsdEstimated(id));
        line("staffingReview" + k, () -> company.staff().staffingReview(id));
        line("budgetLeftUsd" + k, () -> company.verification().budgetLeftUsd(id));
        line("budgetReached" + k, () -> company.verification().budgetReached(id));
        line("lastActivity" + k, () -> company.queries().lastActivity(id));
        CommandContext director = new CommandContext(Actor.director(), m.origin(), NOW, null);
        refusal("checkCompleteMission" + k, company.missions().refusalToComplete(director, id));
        check("checkPullRequest" + k, () -> company.deliveries().checkPullRequest(director, id));
        for (Workspace w : company.workshop().workspaces(id)) {
            String kw = k + " " + w.agent().value();
            check("checkWorkshopAccess" + kw, () -> company.workshop().checkAccess(director, id, w.agent()));
            check("checkChecksCanRun" + kw, () -> company.verification().checkChecksCanRun(director, id, w.agent()));
        }
        company.tickets().of(id).forEach(t -> ticket(t, director));
        company.desk().of(id).forEach(this::question);
        company.sessions().of(id).forEach(this::session);
    }

    private void ticket(Ticket t, CommandContext director) {
        String k = " " + t.id().value();
        line("ticket" + k, () -> company.tickets().find(t.id()));
        line("merge" + k, () -> company.workshop().merge(t.id()));
        line("isStaffingTicket" + k, () -> company.staff().isStaffingTicket(t.id()));
        line("commitFor" + k, () -> company.queries().commitFor(t.id()));
        line("checkDirective" + k, () -> company.workshop().checkDirective(director, t.id()));
    }

    private void question(Question q) {
        String k = " " + q.id().value();
        line("question" + k, () -> company.desk().find(q.id()));
        line("remembered" + k, () -> company.desk().remembered(q.id()));
    }

    private void session(AgentSession s) {
        String k = " " + s.id();
        line("session" + k, () -> company.sessions().find(s.id()));
        line("modelOfSession" + k, () -> company.sessions().modelOf(s.id()));
        line(
                "continuationRequested" + k,
                () -> company.sessions().continuationRequested(s.mission(), s.ticket(), s.agent()));
    }

    private void project(String path) {
        line("projectMark " + path, () -> company.projects().mark(path));
        line("projectForgotten " + path, () -> company.projects().forgotten(path));
    }

    private Set<String> models() {
        Set<String> models = new LinkedHashSet<>();
        company.staff().all().forEach(a -> models.add(a.model()));
        company.missions().all().forEach(m -> company.sessions().of(m.id()).forEach(s -> models.add(s.model())));
        models.remove(null);
        return models;
    }

    private Set<String> projects() {
        Set<String> paths = new LinkedHashSet<>();
        company.missions().all().forEach(m -> paths.add(m.workspace()));
        paths.remove(null);
        paths.add("C:/never/worked/on");
        return paths;
    }

    private void line(String name, Supplier<?> query) {
        out.append(name).append(" = ");
        try {
            out.append(Canonical.of(query.get()));
        } catch (DomainException e) {
            out.append("refused ").append(e.code()).append(": ").append(e.getMessage());
        } catch (RuntimeException e) {
            out.append("threw ").append(e.getClass().getName());
        }
        out.append('\n');
    }

    /** The same line as {@link #check} for a check read as a value. */
    private void refusal(String name, java.util.Optional<dev.floor42.domain.Refused> refused) {
        out.append(name).append(" = ");
        refused.ifPresentOrElse(
                r -> out.append("refused ").append(r.code()).append(": ").append(r.message()),
                () -> out.append(Canonical.of("ok")));
        out.append('\n');
    }

    private void check(String name, Runnable check) {
        line(name, () -> {
            check.run();
            return "ok";
        });
    }
}
