package dev.floor42.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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

/** Module boundaries of ADR-0003, checked on every build. */
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
            "dev.floor42.server.app.MissionScheduler",
            "the one place background work runs, with its shutdown and its wake-ups",
            "dev.floor42.server.app.BoundedProcess",
            "reads the output of the checks or of a proof step while the caller waits for the process; joined before"
                    + " the result is returned",
            "dev.floor42.server.app.DirectorTerminals",
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
