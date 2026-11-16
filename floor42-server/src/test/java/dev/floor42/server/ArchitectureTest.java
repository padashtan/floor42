package dev.floor42.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

/** Module boundaries of ADR-0003 and the feature contexts of ADR-0067, checked on every build. */
@AnalyzeClasses(
        packages = "dev.floor42",
        importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeTestJars.class})
class ArchitectureTest {

    /** The domain's test jar (the golden journals' tests, used by the server's tests) is test code too. */
    public static final class DoNotIncludeTestJars implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("-tests.jar");
        }
    }

    /** The domain is the source of truth and must stay framework-free. */
    @ArchTest
    static final ArchRule domainDependsOnJdkOnly = noClasses()
            .that()
            .resideInAPackage("dev.floor42.domain..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("dev.floor42.domain..", "java..");

    /** Runtimes are adapters behind a port: they know the domain vocabulary, not the application or the API. */
    @ArchTest
    static final ArchRule runtimesDoNotReachIntoTheApplication = noClasses()
            .that()
            .resideInAPackage("dev.floor42.server.runtime..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.floor42.server.app..", "dev.floor42.server.api..", "dev.floor42.server.journal..");

    /**
     * Identity is reached by the runtimes (the hook path), so it may not reach the application either, or a runtime
     * would get there through it. It asks its questions through ports it owns (OfficeFacts).
     */
    @ArchTest
    static final ArchRule iamDoesNotReachIntoTheApplication = noClasses()
            .that()
            .resideInAPackage("dev.floor42.server.iam..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.floor42.server.app..", "dev.floor42.server.api..", "dev.floor42.server.journal..");

    /** Storage knows events, nothing about use cases or HTTP. */
    @ArchTest
    static final ArchRule journalIsLowLevel = noClasses()
            .that()
            .resideInAPackage("dev.floor42.server.journal..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.floor42.server.app..", "dev.floor42.server.api..", "dev.floor42.server.runtime..");

    /** Nothing but the HTTP layer depends on the HTTP layer. */
    @ArchTest
    static final ArchRule apiIsALeaf = noClasses()
            .that()
            .resideOutsideOfPackage("dev.floor42.server.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.floor42.server.api..");

    /** Access control does not depend on business state: it cannot be bypassed by a domain bug. */
    @ArchTest
    static final ArchRule securityIsIndependent = noClasses()
            .that()
            .resideInAPackage("dev.floor42.server.security..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.floor42.domain..", "dev.floor42.server.app..", "dev.floor42.server.journal..");

    /**
     * The contexts of the application (ADR-0067) and the ones each may use. The graph is acyclic: a context lists only
     * contexts below it, boot wires them all and nothing uses boot. A new context needs its line here.
     */
    static final Map<String, Set<String>> APP_CONTEXTS = Map.ofEntries(
            Map.entry("core", Set.of()),
            Map.entry("workspace", Set.of("core")),
            Map.entry("mission", Set.of("core", "workspace")),
            Map.entry("delivery", Set.of("core", "workspace", "mission")),
            Map.entry("session", Set.of("core", "workspace", "mission", "delivery")),
            Map.entry("committee", Set.of("core", "workspace", "mission", "session")),
            Map.entry("asset", Set.of("core", "session")),
            Map.entry("demo", Set.of("core", "workspace", "session")),
            Map.entry("terminal", Set.of("core")),
            Map.entry("cost", Set.of("core")),
            Map.entry("office", Set.of()),
            Map.entry(
                    "boot",
                    Set.of(
                            "core",
                            "workspace",
                            "mission",
                            "delivery",
                            "session",
                            "committee",
                            "asset",
                            "demo",
                            "terminal",
                            "cost")));

    /**
     * The contexts of the HTTP layer and the API contexts each may use. Every one may use the shared plumbing (the
     * package api itself), which uses none of them. The mission's read model is used by the resources that answer
     * with it; only the journal's pages render events.
     */
    static final Map<String, Set<String>> API_CONTEXTS = Map.ofEntries(
            Map.entry("mission", Set.of()),
            Map.entry("delivery", Set.of("mission")),
            Map.entry("committee", Set.of("mission")),
            Map.entry("cost", Set.of("mission")),
            Map.entry("journal", Set.of("event")),
            Map.entry("event", Set.of()),
            Map.entry("workspace", Set.of()),
            Map.entry("session", Set.of()),
            Map.entry("asset", Set.of()),
            Map.entry("terminal", Set.of()),
            Map.entry("demo", Set.of()),
            Map.entry("console", Set.of()),
            Map.entry("iam", Set.of()),
            Map.entry("gateway", Set.of("mission", "cost")),
            Map.entry("office", Set.of()));

    /** The context of a class under {@code root}: the first package segment below it, "" for the root itself. */
    static String contextOf(JavaClass c, String root) {
        String p = c.getPackageName();
        if (p.equals(root)) return "";
        if (!p.startsWith(root + ".")) return null;
        String rest = p.substring(root.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** Classes under root depend only on their own context, the root, and the contexts their line allows. */
    static ArchRule contextsUseOnlyWhatTheyList(String root, Map<String, Set<String>> allowed, boolean rootIsShared) {
        return classes()
                .that()
                .resideInAPackage(root + "..")
                .should(new ArchCondition<JavaClass>("depend only on the contexts listed for theirs") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        String mine = contextOf(c, root);
                        c.getDirectDependenciesFromSelf().forEach(d -> {
                            String theirs = contextOf(d.getTargetClass(), root);
                            if (theirs == null || theirs.equals(mine)) return;
                            boolean ok = (rootIsShared && theirs.isEmpty())
                                    || (mine != null
                                            && !mine.isEmpty()
                                            && allowed.getOrDefault(mine, Set.of())
                                                    .contains(theirs));
                            if (!ok)
                                events.add(SimpleConditionEvent.violated(
                                        c,
                                        "context " + (mine.isEmpty() ? "(root)" : mine) + " uses "
                                                + (theirs.isEmpty() ? "(root)" : theirs) + ": " + d.getDescription()));
                        });
                    }
                });
    }

    /** Every class of the application sits in a listed context (ADR-0067). */
    @ArchTest
    static final ArchRule appClassesSitInAListedContext = classes()
            .that()
            .resideInAPackage("dev.floor42.server.app..")
            .should(new ArchCondition<JavaClass>("sit in a context listed in APP_CONTEXTS") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    if (!APP_CONTEXTS.containsKey(contextOf(c, "dev.floor42.server.app")))
                        events.add(SimpleConditionEvent.violated(c, c.getName() + " is in no listed context"));
                }
            });

    /** Every class of the HTTP layer sits in the shared plumbing or in a listed context. */
    @ArchTest
    static final ArchRule apiClassesSitInAListedContext = classes()
            .that()
            .resideInAPackage("dev.floor42.server.api..")
            .should(new ArchCondition<JavaClass>("sit in the package api or a context listed in API_CONTEXTS") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    String ctx = contextOf(c, "dev.floor42.server.api");
                    if (!ctx.isEmpty() && !API_CONTEXTS.containsKey(ctx))
                        events.add(SimpleConditionEvent.violated(c, c.getName() + " is in no listed context"));
                }
            });

    /** The application's contexts use only the contexts their line lists. */
    @ArchTest
    static final ArchRule appContextsFollowTheirGraph =
            contextsUseOnlyWhatTheyList("dev.floor42.server.app", APP_CONTEXTS, false);

    /** The API's contexts use only the shared plumbing and the API contexts their line lists. */
    @ArchTest
    static final ArchRule apiContextsFollowTheirGraph =
            contextsUseOnlyWhatTheyList("dev.floor42.server.api", API_CONTEXTS, true);

    /** No cycle between the application's contexts, whatever the table says. */
    @ArchTest
    static final ArchRule appContextsAreFreeOfCycles =
            slices().matching("dev.floor42.server.app.(*)..").should().beFreeOfCycles();

    /** No cycle between the API's contexts. */
    @ArchTest
    static final ArchRule apiContextsAreFreeOfCycles =
            slices().matching("dev.floor42.server.api.(*)..").should().beFreeOfCycles();

    /**
     * Classes of the domain that may extend another class of the domain, each with its reason. None today: the company
     * is a root over area facades and area models, by composition, never a chain of superclasses cut to keep files
     * short. Records, enums and interfaces (the event hierarchy) are not concerned.
     */
    private static final Set<String> DOMAIN_SUBCLASSES_ALLOWED = Set.of();

    /** Composition, not inheritance, inside the domain: no chain like Company, CompanyShop, CompanyWork... again. */
    @ArchTest
    static final ArchRule domainClassesDoNotExtendDomainClasses = classes()
            .that()
            .resideInAPackage("dev.floor42.domain..")
            .and()
            .areNotInterfaces()
            .and()
            .areNotAnonymousClasses()
            .and(DescribedPredicate.describe(
                    "are not listed in DOMAIN_SUBCLASSES_ALLOWED",
                    c -> !DOMAIN_SUBCLASSES_ALLOWED.contains(c.getName())))
            .should(new ArchCondition<JavaClass>("extend no other class of the domain") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    c.getRawSuperclass()
                            .filter(s -> s.getPackageName().startsWith("dev.floor42.domain"))
                            .ifPresent(s -> events.add(
                                    SimpleConditionEvent.violated(c, c.getName() + " extends " + s.getName())));
                }
            });

    /**
     * Who may start a thread besides {@code MissionScheduler}, each with its reason. Background work of the company
     * (retrospectives, staffing, the demo, dispatch) forks through the scheduler, which stops taking work at shutdown
     * and gives the work under way a grace. What is left here is not background work: each thread lives and dies with
     * one process or one timer its owner closes, or sits in a package that may not reach the application.
     */
    static final Map<String, String> THREADS_ALLOWED = Map.of(
            "dev.floor42.server.app.core.MissionScheduler",
            "the one place background work runs, with its shutdown and its wake-ups",
            "dev.floor42.server.app.core.BoundedProcess",
            "reads the output of the checks or of a proof step while the caller waits for the process; joined before"
                    + " the result is returned",
            "dev.floor42.server.app.terminal.DirectorTerminals",
            "pumps and watches one PTY for the life of the director's terminal, and polls its status line; closed with"
                    + " the terminal and at shutdown",
            "dev.floor42.server.api.SocketSessions",
            "the 30 s sweep that closes the sockets and shells of an ended session, a daemon thread ended at shutdown",
            "dev.floor42.server.journal.JournalBackups",
            "the daily copy's timer; the journal may not depend on the application (journalIsLowLevel)",
            "dev.floor42.server.iam.IdentityWatch",
            "the 60 s identity check's timer, a daemon thread ended at shutdown; never on the hook path",
            "dev.floor42.server.runtime.ClaudeCodeRuntime",
            "one watchdog per session process, ended with it; runtimes may not reach the application",
            "dev.floor42.server.runtime.SandboxProbe",
            "reads the probe's output while the caller waits for the process; runtimes may not reach the application",
            "dev.floor42.server.security.DirectorSignIn",
            "removes the sign-in link file at its expiry, one daemon thread that holds nothing else; never on a request"
                    + " path");

    private static final Set<String> THREAD_FACTORIES =
            Set.of("ofVirtual", "ofPlatform", "startVirtualThread", "start", "<init>");

    /** A call that starts or builds a thread or a pool of them. */
    static boolean startsAThread(JavaCall<?> call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        if (owner.equals(Thread.class.getName())) return THREAD_FACTORIES.contains(name) && !name.equals("start");
        if (call.getTargetOwner().isAssignableTo(Thread.class) && name.equals("<init>")) return true;
        return owner.equals(Executors.class.getName())
                || owner.equals(ForkJoinPool.class.getName())
                || (owner.equals(CompletableFuture.class.getName()) && name.endsWith("Async"));
    }

    private static JavaClass outermostOf(JavaClass c) {
        JavaClass k = c;
        while (k.getEnclosingClass().isPresent()) k = k.getEnclosingClass().get();
        return k;
    }

    /** No thread starts outside MissionScheduler and the justified list above. */
    @ArchTest
    static final ArchRule threadsStartOnlyInTheScheduler = noClasses()
            .that(DescribedPredicate.describe(
                    "are not listed in THREADS_ALLOWED",
                    c -> !THREADS_ALLOWED.containsKey(outermostOf(c).getName())))
            .should(new ArchCondition<JavaClass>("start a thread or a thread pool") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    c.getAccessesFromSelf().stream()
                            .filter(a -> a instanceof JavaCall<?> call && startsAThread(call))
                            .forEach(call -> events.add(SimpleConditionEvent.satisfied(c, call.getDescription())));
                }
            });
}
