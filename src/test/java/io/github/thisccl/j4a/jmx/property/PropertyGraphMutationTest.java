package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PropertyGraphMutationTest {
    private static final String STRING = StringProperty.class.getName();
    private static final String INTEGER = IntegerProperty.class.getName();
    private static final String COLLECTION = CollectionProperty.class.getName();
    private static final String MAP = MapProperty.class.getName();
    private static final String ELEMENT = TestElementProperty.class.getName();

    @BeforeAll
    static void selectSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void appliesMixedGraphWritesToCandidateOnlyWithImmutableScopedReceipt() throws Exception {
        ConfigTestElement source = fixture();
        byte[] sourceBefore = Todo8OpaqueFixtures.saveElement(source);
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = withDeclaredAbsent(
                graph.inspect(source, Todo8OpaqueFixtures.runtimeContext()));
        List<PropertyWrite> writes = mixedWrites(candidate);

        MutationReceipt receipt = graph.apply(candidate, snapshot, writes);

        assertThat(Todo8OpaqueFixtures.saveElement(source)).containsExactly(sourceBefore);
        assertThat(candidate.getPropertyAsString("qa.scalar")).isEqualTo("after");
        assertThat(values(candidate.getPropertyOrNull("qa.collection")))
                .containsExactly("duplicate", "duplicate", "last");
        assertThat(values(candidate.getPropertyOrNull("qa.empty"))).isEmpty();
        assertThat(mapKeys((MapProperty) candidate.getPropertyOrNull("qa.map")))
                .containsExactly("7", "true", "nested");
        assertThat(((TestElementProperty) candidate.getPropertyOrNull("qa.element"))
                .getElement().getPropertyAsInt("qa.count")).isEqualTo(41);
        assertThat(((TestElementProperty) candidate.getPropertyOrNull("qa.nested"))
                .getElement().getPropertyAsInt("qa.count")).isEqualTo(73);
        assertThat(values(candidate.getPropertyOrNull("qa.opaque")))
                .containsExactly("opaque-after", "opaque-tail");
        assertThat(candidate.getPropertyOrNull("qa.absent")).isNull();

        assertThat(receipt.runtimeContext()).isEqualTo(snapshot.runtimeContext());
        assertThat(receipt.writes()).extracting(write -> write.property().segments())
                .containsExactlyElementsOf(paths(writes));
        assertThat(receipt.writes()).hasSize(writes.size());
        assertThat(receipt.writes().get(3).value().entries())
                .extracting(entry -> entry.key().type())
                .containsOnly(GraphType.STRING);
        assertThat(receipt.writes().get(6).value().opaqueValue().baseDigest())
                .isNotEqualTo(writes.get(6).value().opaqueValue().baseDigest());
        assertThatThrownBy(() -> receipt.writes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> receipt.writes().get(1).value().items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolvesEveryWriteBeforeMutationAndRejectsMalformedOrStaleInput() throws Exception {
        assertRejectedWithoutMutation(Arrays.asList(
                write("qa.unknown", string("value"))));
        assertRejectedWithoutMutation(Arrays.asList(
                write("qa.nested/qa.unknown", string("value"))));
        PropertyWrite duplicate = write("qa.scalar", string("after"));
        assertRejectedWithoutMutation(Arrays.asList(duplicate, duplicate));
        assertRejectedWithoutMutation(Arrays.asList(new PropertyWrite(
                path("qa.scalar"), GraphType.INT,
                RecursiveValue.scalar(GraphType.INT, INTEGER, 9))));

        ConfigTestElement source = fixture();
        ConfigTestElement staleCandidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        staleCandidate.setProperty("qa.scalar", "stale-drift");
        byte[] before = Todo8OpaqueFixtures.saveElement(staleCandidate);

        assertThatThrownBy(() -> graph.apply(staleCandidate, snapshot,
                Collections.singletonList(write("qa.scalar", string("after")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale graph snapshot")
                .hasMessageContaining("[qa.scalar]");
        assertThat(Todo8OpaqueFixtures.saveElement(staleCandidate)).containsExactly(before);

        ConfigTestElement opaqueSource = fixture();
        ConfigTestElement opaqueCandidate = (ConfigTestElement) opaqueSource.clone();
        GraphSnapshot opaqueSnapshot = graph.inspect(
                opaqueSource, Todo8OpaqueFixtures.runtimeContext());
        OpaquePropertyCodec opaqueCodec = new OpaquePropertyCodec(opaqueSnapshot.runtimeContext());
        RecursiveValue.OpaqueValue current = opaqueCodec.read(
                opaqueCandidate.getPropertyOrNull("qa.opaque"));
        RecursiveValue.OpaqueValue stale = Todo8OpaqueFixtures.withDigest(
                current, repeat('0', 64));
        byte[] opaqueBefore = Todo8OpaqueFixtures.saveElement(opaqueCandidate);

        assertThatThrownBy(() -> graph.apply(opaqueCandidate, opaqueSnapshot,
                Collections.singletonList(new PropertyWrite(path("qa.opaque"), GraphType.OPAQUE,
                        RecursiveValue.opaque(COLLECTION, stale)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base digest is stale");
        assertThat(Todo8OpaqueFixtures.saveElement(opaqueCandidate))
                .containsExactly(opaqueBefore);
    }

    @Test
    void scalarArrayCollectionIndexFromReadCanBeAppliedAndReloadedUnchanged() throws Exception {
        ConfigTestElement source = nestedContainerFixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyPath nestedPath = nestedPath();

        GraphNode discovered = snapshot.resolveWritable(nestedPath);
        assertThat(documentPaths(new PropertyGraphDocumentMapper()
                .topLevelDocuments(snapshot, false))).contains(nestedAddress());

        MutationReceipt receipt = graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(nestedPath, GraphType.STRING,
                        RecursiveValue.scalar(GraphType.STRING, STRING, "nested-changed"))));
        graph.project(candidate, receipt);
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));

        assertThat(discovered.value().scalarValue()).isEqualTo("nested-before");
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .resolve(nestedPath).value().scalarValue()).isEqualTo("nested-changed");
        assertThat(graph.inspect(source, Todo8OpaqueFixtures.runtimeContext())
                .resolve(nestedPath).value().scalarValue()).isEqualTo("nested-before");
    }

    @Test
    void scalarArrayMapKeyFromReadCanBeAppliedAndReloadedUnchanged() throws Exception {
        ConfigTestElement source = nestedContainerFixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyPath nestedPath = io.github.thisccl.j4a.path.TestPropertyPaths.path(
                io.github.thisccl.j4a.path.TestPropertyPaths.property("qa.map"),
                io.github.thisccl.j4a.path.TestPropertyPaths.key("row"),
                io.github.thisccl.j4a.path.TestPropertyPaths.property("qa.count"));

        GraphNode discovered = snapshot.resolveWritable(nestedPath);
        assertThat(documentPaths(new PropertyGraphDocumentMapper()
                .topLevelDocuments(snapshot, false)))
                .contains(Arrays.<Object>asList("qa.map", "row", "qa.count"));

        MutationReceipt receipt = graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(nestedPath, GraphType.INT,
                        RecursiveValue.scalar(GraphType.INT, INTEGER, 19))));
        graph.project(candidate, receipt);
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));

        assertThat(discovered.value().scalarValue()).isEqualTo(7);
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .resolve(nestedPath).value().scalarValue()).isEqualTo(19);
        assertThat(graph.inspect(source, Todo8OpaqueFixtures.runtimeContext())
                .resolve(nestedPath).value().scalarValue()).isEqualTo(7);
    }

    @Test
    void directCollectionScalarLeafCanBeAppliedAndReloadedWithoutChangingSiblings()
            throws Exception {
        ConfigTestElement source = fixture();
        source.setProperty(collection("qa.collection", "first", "middle", "last"));
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyPath directPath = io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "qa.collection", Integer.valueOf(0));

        GraphNode discovered = snapshot.resolveWritable(directPath);
        MutationReceipt receipt = graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(directPath, GraphType.STRING,
                        RecursiveValue.scalar(GraphType.STRING, STRING, "changed-index"))));
        graph.project(candidate, receipt);
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));

        assertThat(discovered.value().scalarValue()).isEqualTo("first");
        assertThat(values(candidate.getPropertyOrNull("qa.collection")))
                .containsExactly("changed-index", "middle", "last");
        assertThat(values(reloaded.getPropertyOrNull("qa.collection")))
                .containsExactly("changed-index", "middle", "last");
        assertThat(values(source.getPropertyOrNull("qa.collection")))
                .containsExactly("first", "middle", "last");
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .resolve(directPath).value().scalarValue()).isEqualTo("changed-index");
    }

    @Test
    void directMapScalarLeafCanBeAppliedAndReloadedWithoutChangingKeyOrder()
            throws Exception {
        ConfigTestElement source = fixture();
        Map<String, JMeterProperty> entries = new LinkedHashMap<String, JMeterProperty>();
        entries.put("first", new StringProperty("first", "before"));
        entries.put("second", new StringProperty("second", "untouched"));
        source.setProperty(new MapProperty("qa.direct_map", entries));
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyPath directPath = io.github.thisccl.j4a.path.TestPropertyPaths.path(
                io.github.thisccl.j4a.path.TestPropertyPaths.property("qa.direct_map"),
                io.github.thisccl.j4a.path.TestPropertyPaths.key("first"));

        GraphNode discovered = snapshot.resolveWritable(directPath);
        MutationReceipt receipt = graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(directPath, GraphType.STRING,
                        RecursiveValue.scalar(GraphType.STRING, STRING, "changed-entry"))));
        graph.project(candidate, receipt);
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        MapProperty changed = (MapProperty) candidate.getPropertyOrNull("qa.direct_map");
        MapProperty persisted = (MapProperty) reloaded.getPropertyOrNull("qa.direct_map");

        assertThat(discovered.value().scalarValue()).isEqualTo("before");
        assertThat(mapKeys(changed)).containsExactly("first", "second");
        assertThat(changed.get("first").getStringValue()).isEqualTo("changed-entry");
        assertThat(changed.get("second").getStringValue()).isEqualTo("untouched");
        assertThat(mapKeys(persisted)).containsExactly("first", "second");
        assertThat(persisted.get("first").getStringValue()).isEqualTo("changed-entry");
        assertThat(persisted.get("second").getStringValue()).isEqualTo("untouched");
        assertThat(((MapProperty) source.getPropertyOrNull("qa.direct_map"))
                .get("first").getStringValue()).isEqualTo("before");
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .resolve(directPath).value().scalarValue()).isEqualTo("changed-entry");
    }

    @Test
    void directMapScalarLeafCanBeRemovedAndReloadedAsAbsent() throws Exception {
        ConfigTestElement source = fixture();
        Map<String, JMeterProperty> entries = new LinkedHashMap<String, JMeterProperty>();
        entries.put("first", new StringProperty("first", "before"));
        entries.put("removed", new StringProperty("removed", "delete-me"));
        entries.put("second", new StringProperty("second", "after"));
        source.setProperty(new MapProperty("qa.direct_map", entries));
        List<String> sourceKeys = mapKeys(
                (MapProperty) source.getPropertyOrNull("qa.direct_map"));
        List<String> retainedKeys = new ArrayList<String>(sourceKeys);
        retainedKeys.remove("removed");
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyPath removedPath = io.github.thisccl.j4a.path.TestPropertyPaths.path(
                io.github.thisccl.j4a.path.TestPropertyPaths.property("qa.direct_map"),
                io.github.thisccl.j4a.path.TestPropertyPaths.key("removed"));
        PropertyWrite removal = new PropertyWrite(removedPath, GraphType.STRING,
                RecursiveValue.absent(GraphType.STRING, STRING));

        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(removal));
        VerificationProjection projection = graph.project(candidate, receipt);
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        VerificationProjection reloadedProjection = graph.project(reloaded, receipt);
        MapProperty persisted = (MapProperty) reloaded.getPropertyOrNull("qa.direct_map");

        assertThat(receipt.writes().get(0)).isEqualTo(removal);
        assertThat(projection.values().get(0)).isEqualTo(removal);
        assertThat(reloadedProjection.values().get(0)).isEqualTo(removal);
        assertThat(mapKeys(persisted)).containsExactlyElementsOf(retainedKeys);
        assertThat(persisted.get("removed")).isNull();
        assertThat(persisted.get("first").getStringValue()).isEqualTo("before");
        assertThat(persisted.get("second").getStringValue()).isEqualTo("after");
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .find(removedPath)).isEmpty();
        assertThat(mapKeys((MapProperty) source.getPropertyOrNull("qa.direct_map")))
                .containsExactlyElementsOf(sourceKeys);
    }

    @Test
    void finalCollectionScalarLeafCanBeRemovedAndReloadedAsAbsent() throws Exception {
        ConfigTestElement source = fixture();
        source.setProperty(collection("qa.collection", "first", "middle", "removed"));
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyPath removedPath = io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "qa.collection", Integer.valueOf(2));
        PropertyWrite removal = new PropertyWrite(removedPath, GraphType.STRING,
                RecursiveValue.absent(GraphType.STRING, STRING));

        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(removal));
        VerificationProjection projection = graph.project(candidate, receipt);
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        VerificationProjection reloadedProjection = graph.project(reloaded, receipt);

        assertThat(receipt.writes().get(0)).isEqualTo(removal);
        assertThat(projection.values().get(0)).isEqualTo(removal);
        assertThat(reloadedProjection.values().get(0)).isEqualTo(removal);
        assertThat(values(reloaded.getPropertyOrNull("qa.collection")))
                .containsExactly("first", "middle");
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .find(removedPath)).isEmpty();
        assertThat(values(source.getPropertyOrNull("qa.collection")))
                .containsExactly("first", "middle", "removed");
    }

    @Test
    void middleCollectionScalarLeafRemovalDoesNotProjectShiftedSibling() throws Exception {
        ConfigTestElement source = fixture();
        source.setProperty(collection("qa.collection", "first", "removed", "last"));
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyPath removedPath = io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "qa.collection", Integer.valueOf(1));
        PropertyWrite removal = new PropertyWrite(removedPath, GraphType.STRING,
                RecursiveValue.absent(GraphType.STRING, STRING));

        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(removal));
        VerificationProjection projection = graph.project(candidate, receipt);
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        VerificationProjection reloadedProjection = graph.project(reloaded, receipt);

        assertThat(receipt.writes().get(0)).isEqualTo(removal);
        assertThat(projection.values().get(0)).isEqualTo(removal);
        assertThat(reloadedProjection.values().get(0)).isEqualTo(removal);
        assertThat(values(reloaded.getPropertyOrNull("qa.collection")))
                .containsExactly("first", "last");
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .resolve(removedPath).value().scalarValue()).isEqualTo("last");
        assertThat(values(source.getPropertyOrNull("qa.collection")))
                .containsExactly("first", "removed", "last");

        ((CollectionProperty) reloaded.getPropertyOrNull("qa.collection"))
                .set(1, new StringProperty("last", "changed-after-reload"));
        assertThatThrownBy(() -> graph.project(reloaded, receipt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection mismatch")
                .hasMessageContaining("[qa.collection]");
    }

    @Test
    void readEmittedWholeElementDocumentAppliesProjectsAndReloadsWithValidatedAncestry()
            throws Exception {
        ConfigTestElement source = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(nestedContainerFixture()));
        byte[] sourceBefore = Todo8OpaqueFixtures.saveElement(source);
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyGraphDocumentMapper mapper = new PropertyGraphDocumentMapper();
        List<Object> rootPath = Collections.<Object>singletonList("qa.element");
        Map<String, Object> document = mapper.topLevelDocuments(snapshot, false).stream()
                .filter(item -> rootPath.equals(item.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("qa.element document missing"));
        Object changed = copyReplacingScalarValue(
                document, nestedAddress(), "nested-changed");
        if (!(changed instanceof Map)) {
            throw new AssertionError("copied property graph document is not a mapping");
        }
        Map<?, ?> changedDocument = (Map<?, ?>) changed;
        PropertyWrite requested = mapper.resolve(changedDocument, snapshot);

        assertThat(documentPaths(document)).contains(nestedAddress());
        assertThat(snapshot.resolve(nestedPath()).value().scalarValue())
                .isEqualTo("nested-before");
        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(requested));
        assertThat(graph.project(candidate, receipt).values())
                .containsExactlyElementsOf(receipt.writes());
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        GraphSnapshot reloadedSnapshot = graph.inspect(
                reloaded, Todo8OpaqueFixtures.runtimeContext());
        Object reloadedValue = reloadedSnapshot.resolve(nestedPath()).value().scalarValue();
        assertThat(reloadedValue).isEqualTo("nested-changed");
        assertThat(mapper.topLevelDocuments(reloadedSnapshot, false).stream()
                .filter(item -> rootPath.equals(item.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("reloaded qa.element document missing")))
                .isEqualTo(changedDocument);
        assertThat(Todo8OpaqueFixtures.saveElement(source)).containsExactly(sourceBefore);
        System.out.println("TODO5_H6_READER_CHANGED_RELOAD=" + reloadedValue);
        System.out.println("TODO5_H6_READER_INPUT_PRESERVED=true");

        ConfigTestElement rejected = (ConfigTestElement) source.clone();
        byte[] rejectedBefore = Todo8OpaqueFixtures.saveElement(rejected);
        List<PropertyWrite> children = new ArrayList<PropertyWrite>(
                requested.value().properties());
        PropertyWrite first = children.get(0);
        List<PropertyPathSegment> unrelated = new ArrayList<PropertyPathSegment>(
                first.property().segments());
        unrelated.set(0, PropertyPathSegment.property("unrelated"));
        children.set(0, new PropertyWrite(
                new PropertyPath(unrelated), first.type(), first.value()));
        RecursiveValue mismatched = RecursiveValue.element(
                requested.value().propertyClass(), requested.value().elementClass(), children);

        assertThatThrownBy(() -> graph.apply(rejected, snapshot, Collections.singletonList(
                new PropertyWrite(requested.property(), GraphType.ELEMENT, mismatched))))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessageContaining("does not descend from containing element path");
        assertThat(Todo8OpaqueFixtures.saveElement(rejected)).containsExactly(rejectedBefore);
        System.out.println("TODO5_H6_READER_REJECTED_PRESERVED=true");
    }

    @Test
    void readEmittedMapElementDocumentUsesTypedKeyAncestryAndRejectsMalformedDescendants()
            throws Exception {
        ConfigTestElement source = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(nestedContainerFixture()));
        byte[] sourceBefore = Todo8OpaqueFixtures.saveElement(source);
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyGraphDocumentMapper mapper = new PropertyGraphDocumentMapper();
        List<Object> rootPath = Collections.<Object>singletonList("qa.map");
        Map<String, Object> document = mapper.topLevelDocuments(snapshot, false).stream()
                .filter(item -> rootPath.equals(item.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("qa.map document missing"));

        PropertyWrite requested = mapper.resolve(document, snapshot);
        PropertyWrite nested = requested.value().entries().get(0).value().properties().get(0);
        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(requested));
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));

        assertThat(nested.property().segments())
                .extracting(PropertyPathSegment::kind)
                .containsExactly(
                        PropertyPathSegment.Kind.PROPERTY,
                        PropertyPathSegment.Kind.KEY,
                        PropertyPathSegment.Kind.PROPERTY);
        assertThat(graph.project(reloaded, receipt).values())
                .containsExactlyElementsOf(receipt.writes());
        assertThat(mapper.topLevelDocuments(
                graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext()), false).stream()
                .filter(item -> rootPath.equals(item.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("reloaded qa.map document missing")))
                .isEqualTo(document);

        List<Object> emittedNested = Arrays.<Object>asList("qa.map", "row", "qa.count");
        assertInvalidElementDescendant(mapper, snapshot, document, emittedNested,
                Arrays.<Object>asList("unrelated", "row", "qa.count"));
        assertInvalidElementDescendant(mapper, snapshot, document, emittedNested,
                Arrays.<Object>asList("qa.map", Integer.valueOf(0), "qa.count"));
        assertInvalidElementDescendant(mapper, snapshot, document, emittedNested,
                Arrays.<Object>asList("qa.map", "row", Integer.valueOf(0)));
        assertThat(Todo8OpaqueFixtures.saveElement(source)).containsExactly(sourceBefore);
    }

    @Test
    void nestedTraversalFailsClosedForInvalidMissingAndStaleTypedPaths() throws Exception {
        assertThatThrownBy(() -> io.github.thisccl.j4a.path.TestPropertyPaths.index(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero or greater");

        assertNestedRejectedWithoutMutation(
                io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                        "qa.element", "Arguments.arguments", Integer.valueOf(1), "Argument.value"),
                string("changed"));
        assertNestedRejectedWithoutMutation(
                io.github.thisccl.j4a.path.TestPropertyPaths.path(
                        io.github.thisccl.j4a.path.TestPropertyPaths.property("qa.map"),
                        io.github.thisccl.j4a.path.TestPropertyPaths.key("missing"),
                        io.github.thisccl.j4a.path.TestPropertyPaths.property("qa.count")),
                RecursiveValue.scalar(GraphType.INT, INTEGER, 9));

        ConfigTestElement source = nestedContainerFixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        TestElement arguments = ((TestElementProperty) candidate.getPropertyOrNull("qa.element"))
                .getElement();
        ((MultiProperty) arguments.getPropertyOrNull("Arguments.arguments")).clear();
        byte[] before = Todo8OpaqueFixtures.saveElement(candidate);
        PropertyPath nested = nestedPath();

        assertThatThrownBy(() -> graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(nested, GraphType.STRING, string("changed")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale graph snapshot")
                .hasMessageContaining("[qa.element");
        assertThat(Todo8OpaqueFixtures.saveElement(candidate)).containsExactly(before);
    }

    private static void assertRejectedWithoutMutation(List<PropertyWrite> writes) throws Exception {
        ConfigTestElement source = fixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        byte[] before = Todo8OpaqueFixtures.saveElement(candidate);

        assertThatThrownBy(() -> graph.apply(candidate, snapshot, writes))
                .isInstanceOf(RuntimeException.class);
        assertThat(Todo8OpaqueFixtures.saveElement(candidate)).containsExactly(before);
    }

    private static void assertNestedRejectedWithoutMutation(
            PropertyPath propertyPath, RecursiveValue value) throws Exception {
        ConfigTestElement source = nestedContainerFixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        byte[] before = Todo8OpaqueFixtures.saveElement(candidate);

        assertThatThrownBy(() -> graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(propertyPath, value.type(), value))))
                .isInstanceOf(RuntimeException.class);
        assertThat(Todo8OpaqueFixtures.saveElement(candidate)).containsExactly(before);
    }

    private static List<PropertyWrite> mixedWrites(ConfigTestElement candidate) throws Exception {
        RecursiveValue collection = RecursiveValue.collection(COLLECTION, Arrays.asList(
                string("duplicate"), string("duplicate"), string("last")));
        RecursiveValue map = RecursiveValue.map(MAP, Arrays.asList(
                entry(GraphType.INT, Integer.valueOf(7), string("seven")),
                entry(GraphType.BOOLEAN, Boolean.TRUE,
                        RecursiveValue.scalar(GraphType.INT, INTEGER, 9)),
                entry(GraphType.STRING, "nested", string("map-value"))));
        RecursiveValue element = requestedElement(41);
        OpaquePropertyCodec opaqueCodec = new OpaquePropertyCodec(
                Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue.OpaqueValue current = opaqueCodec.read(
                candidate.getPropertyOrNull("qa.opaque"));
        RecursiveValue.OpaqueValue changed = Todo8OpaqueFixtures.withPayload(
                current, current.payload().replace("opaque-before", "opaque-after"));
        return Arrays.asList(
                write("qa.scalar", string("after")),
                new PropertyWrite(path("qa.collection"), GraphType.COLLECTION, collection),
                new PropertyWrite(path("qa.empty"), GraphType.COLLECTION,
                        RecursiveValue.collection(COLLECTION,
                                Collections.<RecursiveValue>emptyList())),
                new PropertyWrite(path("qa.map"), GraphType.MAP, map),
                new PropertyWrite(path("qa.element"), GraphType.ELEMENT, element),
                new PropertyWrite(path("qa.nested", "qa.count"), GraphType.INT,
                        RecursiveValue.scalar(GraphType.INT, INTEGER, 73)),
                new PropertyWrite(path("qa.opaque"), GraphType.OPAQUE,
                        RecursiveValue.opaque(COLLECTION, changed)),
                new PropertyWrite(path("qa.absent"), GraphType.STRING,
                        RecursiveValue.absent(GraphType.STRING, STRING)));
    }

    static ConfigTestElement fixture() {
        ConfigTestElement outer = element("todo9.fixture.OuterGui", "Todo 9 outer", 3);
        outer.setProperty(new StringProperty("qa.scalar", "before"));
        outer.setProperty(collection("qa.collection", "old"));
        outer.setProperty(collection("qa.empty", "old"));
        Map<String, JMeterProperty> map = new LinkedHashMap<String, JMeterProperty>();
        map.put("old", new StringProperty("old", "value"));
        outer.setProperty(new MapProperty("qa.map", map));
        outer.setProperty(new TestElementProperty(
                "qa.element", element("todo9.fixture.ElementGui", "element", 5)));
        ConfigTestElement nested = element("todo9.fixture.NestedGui", "nested", 7);
        nested.setProperty(collection("qa.values", "one", "two", "three"));
        outer.setProperty(new TestElementProperty("qa.nested", nested));
        outer.setProperty(collection("qa.opaque", "opaque-before", "opaque-tail"));
        return outer;
    }

    private static ConfigTestElement nestedContainerFixture() {
        ConfigTestElement outer = element("todo9.fixture.OuterGui", "nested outer", 1);
        ConfigTestElement arguments = element("todo9.fixture.ArgumentsGui", "arguments", 2);
        ConfigTestElement row = element("todo9.fixture.ArgumentGui", "row", 3);
        row.setProperty(new StringProperty("Argument.value", "nested-before"));
        CollectionProperty rows = new CollectionProperty(
                "Arguments.arguments", Collections.<JMeterProperty>emptyList());
        rows.addProperty(new TestElementProperty("0", row));
        arguments.setProperty(rows);
        outer.setProperty(new TestElementProperty("qa.element", arguments));

        Map<String, JMeterProperty> entries = new LinkedHashMap<String, JMeterProperty>();
        entries.put("row", new TestElementProperty(
                "row", element("todo9.fixture.MapRowGui", "map row", 7)));
        outer.setProperty(new MapProperty("qa.map", entries));
        return outer;
    }

    private static PropertyPath nestedPath() {
        return io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "qa.element", "Arguments.arguments", Integer.valueOf(0), "Argument.value");
    }

    private static List<Object> nestedAddress() {
        return Arrays.<Object>asList(
                "qa.element", "Arguments.arguments", Integer.valueOf(0), "Argument.value");
    }

    private static List<List<?>> documentPaths(Object value) {
        List<List<?>> paths = new ArrayList<List<?>>();
        collectDocumentPaths(value, paths);
        return paths;
    }

    private static void assertInvalidElementDescendant(
            PropertyGraphDocumentMapper mapper,
            GraphSnapshot snapshot,
            Map<String, Object> emitted,
            List<Object> target,
            List<Object> replacement) {
        Object changed = copyReplacingAddress(emitted, target, replacement);
        if (!(changed instanceof Map)) {
            throw new AssertionError("copied property graph document is not a mapping");
        }
        Map<?, ?> document = (Map<?, ?>) changed;
        assertThatThrownBy(() -> mapper.resolve(document, snapshot))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessageContaining(".property: element property path requires one property segment");
    }

    private static Object copyReplacingAddress(
            Object value, List<Object> target, List<Object> replacement) {
        if (value instanceof Map) {
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object copied = "property".equals(entry.getKey()) && target.equals(entry.getValue())
                        ? new ArrayList<Object>(replacement)
                        : copyReplacingAddress(entry.getValue(), target, replacement);
                copy.put(entry.getKey(), copied);
            }
            return copy;
        }
        if (value instanceof List) {
            ArrayList<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                copy.add(copyReplacingAddress(item, target, replacement));
            }
            return copy;
        }
        return value;
    }

    private static Object copyReplacingScalarValue(
            Object value, List<Object> target, Object replacement) {
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            boolean matches = target.equals(source.get("property"));
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object copied = matches && "value".equals(entry.getKey())
                        ? copyScalarPayload(entry.getValue(), replacement)
                        : copyReplacingScalarValue(entry.getValue(), target, replacement);
                copy.put(entry.getKey(), copied);
            }
            return copy;
        }
        if (value instanceof List) {
            ArrayList<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                copy.add(copyReplacingScalarValue(item, target, replacement));
            }
            return copy;
        }
        return value;
    }

    private static Object copyScalarPayload(Object value, Object replacement) {
        if (!(value instanceof Map)) {
            throw new AssertionError("property graph value is not a mapping");
        }
        LinkedHashMap<Object, Object> copy = new LinkedHashMap<Object, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            copy.put(entry.getKey(), "value".equals(entry.getKey())
                    ? replacement
                    : entry.getValue());
        }
        return copy;
    }

    private static void collectDocumentPaths(Object value, List<List<?>> paths) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object property = map.get("property");
            if (property instanceof List<?>) {
                paths.add((List<?>) property);
            }
            for (Object child : map.values()) {
                collectDocumentPaths(child, paths);
            }
        } else if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) {
                collectDocumentPaths(child, paths);
            }
        }
    }

    static GraphSnapshot withDeclaredAbsent(GraphSnapshot source) {
        List<GraphNode> nodes = new ArrayList<GraphNode>(source.nodes());
        GraphCapability capability = new GraphCapability(
                StorageKeyStatus.NON_KEY,
                WritableState.WRITABLE,
                null,
                GraphOwnership.USER,
                RepresentationSource.JMETER_SCHEMA,
                RuntimeClassConstraint.propertyClass(STRING));
        nodes.add(new GraphNode(path("qa.absent"), GraphType.STRING,
                RecursiveValue.absent(GraphType.STRING, STRING), capability));
        return new GraphSnapshot(source.runtimeContext(), nodes);
    }

    static PropertyWrite write(String rawPath, RecursiveValue value) {
        return new PropertyWrite(path(rawPath), value.type(), value);
    }

    static PropertyPath path(String... names) {
        return io.github.thisccl.j4a.path.TestPropertyPaths.properties((Object[]) names);
    }

    static RecursiveValue string(String value) {
        return RecursiveValue.scalar(GraphType.STRING, STRING, value);
    }

    static RecursiveValue requestedElement(int count) {
        return RecursiveValue.element(ELEMENT, ConfigTestElement.class.getName(), Arrays.asList(
                write("TestElement.name", string("changed-element")),
                new PropertyWrite(path("TestElement.enabled"), GraphType.BOOLEAN,
                        RecursiveValue.scalar(GraphType.BOOLEAN,
                                org.apache.jmeter.testelement.property.BooleanProperty.class
                                        .getName(), true)),
                new PropertyWrite(path("qa.count"), GraphType.INT,
                        RecursiveValue.scalar(GraphType.INT, INTEGER, count))));
    }

    private static RecursiveValue.MapEntry entry(
            GraphType type, Object key, RecursiveValue value) {
        return new RecursiveValue.MapEntry(new TypedScalarMapKey(type, key), value);
    }

    private static ConfigTestElement element(String guiClass, String name, int count) {
        ConfigTestElement element = new ConfigTestElement();
        element.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
        element.setProperty(TestElement.GUI_CLASS, guiClass);
        element.setName(name);
        element.setEnabled(true);
        element.setProperty(new IntegerProperty("qa.count", count));
        return element;
    }

    private static CollectionProperty collection(String name, String... values) {
        List<JMeterProperty> items = new ArrayList<JMeterProperty>();
        for (String value : values) {
            items.add(new StringProperty(Integer.toString(value.hashCode()), value));
        }
        return new CollectionProperty(name, items);
    }

    static List<String> values(JMeterProperty property) {
        List<String> values = new ArrayList<String>();
        org.apache.jmeter.testelement.property.PropertyIterator iterator =
                ((CollectionProperty) property).iterator();
        while (iterator.hasNext()) {
            values.add(iterator.next().getStringValue());
        }
        return values;
    }

    private static List<String> mapKeys(MapProperty property) {
        List<String> keys = new ArrayList<String>();
        org.apache.jmeter.testelement.property.PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            keys.add(iterator.next().getName());
        }
        return keys;
    }

    private static List<List<io.github.thisccl.j4a.path.PropertyPathSegment>> paths(
            List<PropertyWrite> writes) {
        List<List<io.github.thisccl.j4a.path.PropertyPathSegment>> paths =
                new ArrayList<List<io.github.thisccl.j4a.path.PropertyPathSegment>>();
        for (PropertyWrite write : writes) {
            paths.add(write.property().segments());
        }
        return paths;
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }
}
