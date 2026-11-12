package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import dev.floor42.server.fixture.ownership.Intruder;
import dev.floor42.server.fixture.ownership.Ledger;
import dev.floor42.server.fixture.ownership.LedgerOwner;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The ownership rules refuse what they claim to, on planted fixtures, and their list of mutators is complete. */
class DomainOwnershipRulesTest {

    static final String FIXTURE = "dev.floor42.server.fixture.ownership.";
    static final Map<String, String> OWNERS = Map.of("Ledger", "LedgerOwner");

    final JavaClasses planted = new ClassFileImporter().importClasses(Ledger.class, LedgerOwner.class, Intruder.class);
    final JavaClasses clean = new ClassFileImporter().importClasses(Ledger.class, LedgerOwner.class);

    @Test
    void aPartsMutatorsCalledByAnotherClassAreRefusedBeyondAddAndRemove() {
        ArchRule rule = DomainOwnershipTest.partsMutatedOnlyByOwners(FIXTURE, OWNERS);
        EvaluationResult result = rule.evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        List<String> details = result.getFailureReport().getDetails();
        for (String method : List.of("removeIf", "merge", "push", "pop", "computeIfAbsent")) {
            assertThat(details).as(method).anyMatch(d -> d.contains("Ledger." + method + "("));
        }
        assertThat(rule.allowEmptyShould(true).evaluate(clean).hasViolation())
                .as("the owner itself")
                .isFalse();
    }

    @Test
    void reachingIntoAnEntitysCollectionOrWritingItsFieldIsRefused() {
        EvaluationResult collections = DomainOwnershipTest.collectionsReachedOnlyByOwners(FIXTURE, OWNERS)
                .evaluate(planted);
        assertThat(collections.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("Intruder.reachIntoTheCollection") && d.contains("Ledger.lines"));
        EvaluationResult fields =
                DomainOwnershipTest.fieldsWrittenOnlyByOwners(FIXTURE, OWNERS).evaluate(planted);
        assertThat(fields.getFailureReport().getDetails())
                .anyMatch(d -> d.contains("Intruder.writeAField") && d.contains("Ledger.label"));
        assertThat(DomainOwnershipTest.collectionsReachedOnlyByOwners(FIXTURE, OWNERS)
                        .allowEmptyShould(true)
                        .evaluate(clean)
                        .hasViolation())
                .isFalse();
    }

    /** Every method name of the collection interfaces is either a mutator or one of these reads. */
    static final Set<String> READS = Set.of(
            "ceiling",
            "ceilingEntry",
            "ceilingKey",
            "comparator",
            "contains",
            "containsAll",
            "containsKey",
            "containsValue",
            "descendingIterator",
            "descendingKeySet",
            "descendingMap",
            "descendingSet",
            "element",
            "entrySet",
            "equals",
            "first",
            "firstEntry",
            "firstKey",
            "floor",
            "floorEntry",
            "floorKey",
            "forEach",
            "forEachRemaining",
            "get",
            "getFirst",
            "getKey",
            "getLast",
            "getOrDefault",
            "getValue",
            "hasNext",
            "hasPrevious",
            "hashCode",
            "headMap",
            "headSet",
            "higher",
            "higherEntry",
            "higherKey",
            "indexOf",
            "isEmpty",
            "iterator",
            "keySet",
            "last",
            "lastEntry",
            "lastIndexOf",
            "lastKey",
            "listIterator",
            "lower",
            "lowerEntry",
            "lowerKey",
            "navigableKeySet",
            "next",
            "nextIndex",
            "parallelStream",
            "peek",
            "peekFirst",
            "peekLast",
            "previous",
            "previousIndex",
            "reversed",
            "sequencedEntrySet",
            "sequencedKeySet",
            "sequencedValues",
            "size",
            "spliterator",
            "stream",
            "subList",
            "subMap",
            "subSet",
            "tailMap",
            "tailSet",
            "toArray",
            "values");

    @Test
    void everyCollectionMethodIsAMutatorOrARead() {
        Set<String> names = new TreeSet<>();
        for (Class<?> c : List.of(
                List.class,
                Set.class,
                Map.class,
                Deque.class,
                NavigableSet.class,
                NavigableMap.class,
                SequencedMap.class,
                Iterator.class,
                ListIterator.class,
                Map.Entry.class)) {
            for (Method m : c.getMethods()) if (!Modifier.isStatic(m.getModifiers())) names.add(m.getName());
        }
        List<String> unknown = new ArrayList<>();
        for (String n : names) if (!DomainOwnershipTest.MUTATORS.contains(n) && !READS.contains(n)) unknown.add(n);
        assertThat(unknown)
                .as("a new JDK collection method: a mutator (DomainOwnershipTest.MUTATORS) or a read (READS)?")
                .isEmpty();
        assertThat(DomainOwnershipTest.MUTATORS).as("no stale mutator").allMatch(names::contains);
    }
}
