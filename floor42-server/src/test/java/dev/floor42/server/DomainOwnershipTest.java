package dev.floor42.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Each area of the company changes only its own entities: no other class writes an entity's fields, reaches into an
 * entity's collections (where {@code t.directives.add(...)} would slip past a rule on field writes), or calls the
 * mutating methods of the entities' parts. Everyone else reads through the entities' public accessors. The rules are
 * built for a package and its owners, so {@code DomainOwnershipRulesTest} proves each fails on planted fixtures.
 */
@AnalyzeClasses(packages = "dev.floor42", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainOwnershipTest {

    private static final String DOMAIN = "dev.floor42.domain.";

    /** Entity to the one area that owns it. */
    private static final Map<String, String> OWNERS = Map.ofEntries(
            Map.entry("Agent", "Roster"),
            Map.entry("Mission", "MissionBook"),
            Map.entry("Ticket", "TicketBoard"),
            Map.entry("Action", "ActionLog"),
            Map.entry("Question", "Desk"),
            Map.entry("AgentSession", "SessionLog"),
            Map.entry("Workspace", "Workbench"),
            Map.entry("Committee", "CommitteeHall"),
            Map.entry("Retro", "Retrospectives"),
            Map.entry("Lesson", "Retrospectives"),
            Map.entry("Note", "Notebook"),
            Map.entry("StaffingReview", "StaffingBoard"),
            Map.entry("ProjectMark", "RecentProjects"));

    /** Parts of an entity whose state changes through methods: who may call them. */
    private static final Map<String, String> MUTATED_BY_METHODS =
            Map.of("PermissionPolicy", "MissionBook", "PlanUsage", "Verification");

    /**
     * Every method name of the JDK's collection interfaces (List, Set, Map, Deque, their navigable and sequenced
     * kinds, iterators and map entries) that changes the collection. A part that grows one of these names is a
     * mutator: its methods mirror the collection it wraps. {@code DomainOwnershipRulesTest} keeps this list complete
     * against the JDK, every other name being listed as a read there.
     */
    static final Set<String> MUTATORS = Set.of(
            "add",
            "addAll",
            "addFirst",
            "addLast",
            "clear",
            "compute",
            "computeIfAbsent",
            "computeIfPresent",
            "merge",
            "offer",
            "offerFirst",
            "offerLast",
            "poll",
            "pollFirst",
            "pollLast",
            "pollFirstEntry",
            "pollLastEntry",
            "pop",
            "push",
            "put",
            "putAll",
            "putFirst",
            "putLast",
            "putIfAbsent",
            "remove",
            "removeAll",
            "removeFirst",
            "removeFirstOccurrence",
            "removeIf",
            "removeLast",
            "removeLastOccurrence",
            "replace",
            "replaceAll",
            "retainAll",
            "set",
            "setValue",
            "sort");

    @ArchTest
    static final ArchRule entityFieldsAreWrittenOnlyByTheirOwner = fieldsWrittenOnlyByOwners(DOMAIN, OWNERS);

    @ArchTest
    static final ArchRule entityCollectionsAreReachedOnlyByTheirOwner = collectionsReachedOnlyByOwners(DOMAIN, OWNERS);

    @ArchTest
    static final ArchRule entityPartsAreMutatedOnlyByTheirOwner = partsMutatedOnlyByOwners(DOMAIN, MUTATED_BY_METHODS);

    static ArchRule fieldsWrittenOnlyByOwners(String pkg, Map<String, String> owners) {
        return CompositeArchRule.of(owners.entrySet().stream()
                        .map(e -> noClasses()
                                .that(areNeither(pkg, e.getKey(), e.getValue()))
                                .should()
                                .setFieldWhere(fieldOf(pkg, e.getKey(), false))
                                .as("only " + e.getValue() + " writes the fields of " + e.getKey()))
                        .toList())
                .as("entity fields are written only by their owner");
    }

    static ArchRule collectionsReachedOnlyByOwners(String pkg, Map<String, String> owners) {
        return CompositeArchRule.of(owners.entrySet().stream()
                        .map(e -> noClasses()
                                .that(areNeither(pkg, e.getKey(), e.getValue()))
                                .should()
                                .getFieldWhere(fieldOf(pkg, e.getKey(), true))
                                .as("only " + e.getValue() + " reaches the collections of " + e.getKey()))
                        .toList())
                .as("entity collections are reached only by their owner");
    }

    static ArchRule partsMutatedOnlyByOwners(String pkg, Map<String, String> parts) {
        return CompositeArchRule.of(parts.entrySet().stream()
                        .map(e -> noClasses()
                                .that(areNeither(pkg, e.getKey(), e.getValue()))
                                .should()
                                .callMethodWhere(mutatorOf(pkg, e.getKey()))
                                .as("only " + e.getValue() + " changes a " + e.getKey()))
                        .toList())
                .as("entity parts are changed only by their owner");
    }

    private static DescribedPredicate<JavaClass> areNeither(String pkg, String entity, String owner) {
        Set<String> allowed = Set.of(pkg + entity, pkg + owner);
        return DescribedPredicate.describe(
                "are neither " + entity + " nor " + owner,
                c -> !allowed.contains(c.getName())
                        && !allowed.contains(outermost(c).getName()));
    }

    private static JavaClass outermost(JavaClass c) {
        JavaClass k = c;
        while (k.getEnclosingClass().isPresent()) k = k.getEnclosingClass().get();
        return k;
    }

    private static DescribedPredicate<JavaFieldAccess> fieldOf(String pkg, String entity, boolean collectionsOnly) {
        return DescribedPredicate.describe(
                (collectionsOnly ? "a collection field of " : "a field of ") + entity,
                a -> a.getTargetOwner().getName().equals(pkg + entity)
                        && (!collectionsOnly
                                || a.getTarget()
                                        .resolveMember()
                                        .map(DomainOwnershipTest::isCollection)
                                        .orElse(false)));
    }

    private static boolean isCollection(JavaField f) {
        return f.getRawType().isAssignableTo(Collection.class) || f.getRawType().isAssignableTo(Map.class);
    }

    private static DescribedPredicate<JavaMethodCall> mutatorOf(String pkg, String part) {
        return DescribedPredicate.describe(
                "a mutating method of " + part,
                c -> c.getTargetOwner().getName().equals(pkg + part) && MUTATORS.contains(c.getName()));
    }
}
