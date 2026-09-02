package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.util.HTTPFileArgs;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElementSchema;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.testelement.schema.StringPropertyDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

class RuntimeStructuredRowObservationTest {
    @BeforeAll
    static void initializeSelectedSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void observesPopulatedArgumentsFromEveryRuntimeRow() {
        Arguments arguments = new Arguments();
        arguments.addArgument(new Argument("first", "1", "=", "one"));
        arguments.addArgument(new Argument("second", "2", ":", "two"));

        Optional<StructuredRowShape> result = RuntimeStructuredRows.observe(
                new TestElementProperty("arguments", arguments), context());

        assertThat(result).isPresent();
        StructuredRowShape shape = result.get();
        assertThat(shape.outerPropertyClass()).isEqualTo(TestElementProperty.class.getName());
        assertThat(shape.outerValueClass()).isEqualTo(Arguments.class.getName());
        assertThat(shape.storagePropertyClass()).isEqualTo(CollectionProperty.class.getName());
        assertThat(shape.rowWrapperClass()).contains(TestElementProperty.class.getName());
        assertThat(shape.rowClass()).isEqualTo(Argument.class.getName());
        assertThat(shape.fields()).extracting(StructuredRowField::name).containsExactly(
                Argument.ARG_NAME, Argument.VALUE, Argument.METADATA, Argument.DESCRIPTION);
        assertThat(shape.fields()).extracting(StructuredRowField::type)
                .containsExactly(GraphType.STRING, GraphType.STRING, GraphType.STRING, GraphType.STRING);
        assertThat(shape.observedValue().rows()).containsExactly(
                row(Argument.ARG_NAME, "first", Argument.VALUE, "1", Argument.METADATA, "=",
                        Argument.DESCRIPTION, "one"),
                row(Argument.ARG_NAME, "second", Argument.VALUE, "2", Argument.METADATA, ":",
                        Argument.DESCRIPTION, "two"));
        assertThat(shape.emptyEvidence()).isFalse();
        assertThat(shape.reconstruction().listMutator()).isEqualTo("setArguments");
        assertThat(shape.runtimeFingerprint()).isEqualTo(context().fingerprint());
    }

    @Test
    void observesPopulatedDirectHeaderRowsWithTheSamePublicContract() {
        HeaderManager headers = new HeaderManager();
        headers.add(new Header("X-Trace", "one"));
        headers.add(new Header("X-Trace", ""));

        Optional<StructuredRowShape> result = RuntimeStructuredRows.observe(
                headers.getHeaders(), context());

        assertThat(result).isPresent();
        StructuredRowShape shape = result.get();
        assertThat(shape.outerPropertyClass()).isEqualTo(CollectionProperty.class.getName());
        assertThat(shape.storagePropertyClass()).isEqualTo(CollectionProperty.class.getName());
        assertThat(shape.rowWrapperClass()).contains(TestElementProperty.class.getName());
        assertThat(shape.rowClass()).isEqualTo(Header.class.getName());
        assertThat(shape.fields()).extracting(StructuredRowField::name)
                .containsExactly("Header.name", "Header.value");
        assertThat(shape.observedValue().rows()).containsExactly(
                row("Header.name", "X-Trace", "Header.value", "one"),
                row("Header.name", "X-Trace", "Header.value", ""));
    }

    @Test
    void directAndWrappedRowsKeepOnePublicDocumentWhileRetainingReconstructionShape() {
        HeaderManager headers = new HeaderManager();
        headers.add(new Header("X-Direct", "one"));
        Arguments arguments = new Arguments();
        arguments.addArgument(new Argument("wrapped", "two", "=", ""));

        RuntimeStructuredRowDocument.Projection direct = RuntimeStructuredRowDocument.observe(
                headers, headers.getHeaders(), context()).get();
        RuntimeStructuredRowDocument.Projection wrapped = RuntimeStructuredRowDocument.observe(
                new TestElementProperty("arguments", arguments), context()).get();
        StructuredRowShape directShape = RuntimeStructuredRows.observe(headers, headers.getHeaders(), context()).get();
        StructuredRowShape wrappedShape = RuntimeStructuredRows.observe(
                new TestElementProperty("arguments", arguments), context()).get();
        JMeterProperty rebuiltDirect = directShape.materialize(direct.value());
        JMeterProperty rebuiltWrapped = wrappedShape.materialize(wrapped.value());

        assertThat(direct.value()).containsOnlyKeys("row_type", "row_properties", "rows");
        assertThat(wrapped.value()).containsOnlyKeys("row_type", "row_properties", "rows");
        assertThat(RuntimeStructuredRowDocument.observe(rebuiltDirect, context()).get().value())
                .isEqualTo(direct.value());
        assertThat(RuntimeStructuredRowDocument.observe(rebuiltWrapped, context()).get().value())
                .isEqualTo(wrapped.value());
        assertThat(directShape.reconstruction().layout())
                .isEqualTo(StructuredRowReconstruction.Layout.DIRECT);
        assertThat(wrappedShape.reconstruction().layout())
                .isEqualTo(StructuredRowReconstruction.Layout.WRAPPED);
    }

    @Test
    void observesEmptyDirectHeadersWithPristineDescriptorsAndNoRows() {
        HeaderManager headers = new HeaderManager();

        RuntimeStructuredRowDocument.Projection projection =
                RuntimeStructuredRowDocument.observe(
                        headers, headers.getHeaders(), context()).get();

        assertThat(projection.rowType()).isEqualTo(Header.class.getName());
        assertThat(projection.value()).containsEntry("rows", Collections.emptyList());
        assertThat(new ArrayList<Object>((List<?>) projection.value().get("row_properties")))
                .containsExactly(
                descriptor("Header.name", "string", ""),
                descriptor("Header.value", "string", ""));
        assertThat(headers.size()).isZero();
    }

    @Test
    void argumentsEmptyCollectionIsProvenByItsUniqueStorageAccessorAndListMutator() {
        Arguments arguments = new Arguments();
        Optional<StructuredRowShape> result = RuntimeStructuredRows.observe(
                new TestElementProperty("arguments", arguments), context());

        assertThat(result).isPresent();
        assertThat(result.get().rowClass()).isEqualTo(Argument.class.getName());
        assertThat(result.get().emptyEvidence()).isTrue();
        assertThat((Object) arguments.getArguments()).isSameAs(soleStorage(arguments));
    }

    @Test
    void httpFileArgsEmptyCollectionIsProvenByItsExactSelectedStorage() {
        HTTPFileArgs files = new HTTPFileArgs();

        Optional<StructuredRowShape> result = RuntimeStructuredRows.observe(
                new TestElementProperty("files", files), context());

        assertThat(result).isPresent();
        assertThat((Object) files.getHTTPFileArgsCollection()).isSameAs(soleStorage(files));
    }

    @Test
    void observesEmptyOnlyFromUniqueMatchingAccessorMutatorBeanRelationship() {
        Optional<StructuredRowShape> result = RuntimeStructuredRows.observe(
                new TestElementProperty("plugin.rows", new PluginRows()), context());

        assertThat(result).isPresent();
        StructuredRowShape shape = result.get();
        assertThat(shape.rowClass()).isEqualTo(PluginRow.class.getName());
        assertThat(shape.fields()).extracting(StructuredRowField::name).containsExactly(
                "fixture.key", "fixture.payload");
        assertThat(shape.emptyEvidence()).isTrue();
        assertThat(shape.observedValue().rows()).isEmpty();
    }

    @Test
    void mutatorOnlyBeanRelationshipFailsClosed() {
        assertThat(observe(new MutatorOnlyRows())).isEmpty();
    }

    @Test
    void accessorOnlyBeanRelationshipFailsClosed() {
        assertThat(observe(new AccessorOnlyRows())).isEmpty();
    }

    @Test
    void mismatchedGenericRowTypesFailClosed() {
        assertThat(observe(new MismatchedRows())).isEmpty();
    }

    @Test
    void overloadedBeanRelationshipFailsClosed() {
        assertThat(observe(new OverloadedRows())).isEmpty();
    }

    @Test
    void multipleMatchingBeanRelationshipsFailClosed() {
        assertThat(observe(new MultipleRelationshipRows())).isEmpty();
    }

    @Test
    void sameStemAccessorReturningUnrelatedStorageFailsClosed() {
        assertThat(observe(new UnrelatedStorageRows())).isEmpty();
    }

    @Test
    void exactStorageAccessorWithMismatchedStemFailsClosed() {
        assertThat(observe(new MismatchedStemRows())).isEmpty();
    }

    @Test
    void genericObserverHandlesTestOnlyRuntimeFamilyAbsentFromProduction() {
        PluginRows rows = new PluginRows();
        rows.setRows(Arrays.asList(new PluginRow("alpha", "one"), new PluginRow("beta", "two")));

        Optional<StructuredRowShape> result = RuntimeStructuredRows.observe(
                new TestElementProperty("plugin.rows", rows), context());

        assertThat(result).isPresent();
        assertThat(result.get().rowClass()).isEqualTo(PluginRow.class.getName());
        assertThat(result.get().fields()).extracting(StructuredRowField::name)
                .containsExactly("fixture.key", "fixture.payload");
        assertThat(result.get().observedValue().rows()).containsExactly(
                row("fixture.key", "alpha", "fixture.payload", "one"),
                row("fixture.key", "beta", "fixture.payload", "two"));
        System.out.println("TODO5_UNKNOWN_ROWS=" + result.get().observedValue().rows());
    }

    @Test
    void failsClosedForAmbiguousEmptyAndInconsistentPopulatedCollections() {
        AmbiguousRows ambiguous = new AmbiguousRows();
        InconsistentRows inconsistent = new InconsistentRows();

        assertThat(RuntimeStructuredRows.observe(
                new TestElementProperty("ambiguous", ambiguous), context())).isEmpty();
        assertThat(RuntimeStructuredRows.observe(
                new TestElementProperty("inconsistent", inconsistent), context())).isEmpty();
    }

    @Test
    void insufficientEvidenceKeepsTheExistingGenericCollectionRepresentation() {
        CollectionProperty bare = new CollectionProperty("plain", Collections.emptyList());
        bare.addProperty(new StringProperty("item", "value"));

        Optional<StructuredRowShape> rows = RuntimeStructuredRows.observe(bare, context());
        RecursiveValue fallback = RuntimePropertyValueDiscovery.read(bare, context());

        assertThat(rows).isEmpty();
        assertThat(fallback.type()).isEqualTo(GraphType.COLLECTION);
        assertThat(fallback.items()).extracting(RecursiveValue::scalarValue).containsExactly("value");
    }

    private static RuntimeContext context() {
        return new RuntimeContext("todo5", new RuntimeFingerprint(
                "/opt/jmeter-fixture/apache-jmeter-5.6.3", "5.6.3",
                Collections.singletonMap("lib/ext/ApacheJMeter_core.jar", "abc")));
    }

    private static Optional<StructuredRowShape> observe(AbstractTestElement rows) {
        return RuntimeStructuredRows.observe(new TestElementProperty("fixture", rows), context());
    }

    private static java.util.Map<String, Object> row(Object... values) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private static java.util.Map<String, Object> descriptor(
            String name, String type, Object defaultValue) {
        java.util.LinkedHashMap<String, Object> descriptor =
                new java.util.LinkedHashMap<String, Object>();
        descriptor.put("name", name);
        descriptor.put("type", type);
        descriptor.put("required", Boolean.FALSE);
        descriptor.put("default", defaultValue);
        return descriptor;
    }

    private static MultiProperty soleStorage(AbstractTestElement outer) {
        MultiProperty selected = null;
        PropertyIterator properties = outer.propertyIterator();
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            if (property instanceof MultiProperty) {
                assertThat((Object) selected).isNull();
                selected = (MultiProperty) property;
            }
        }
        assertThat((Object) selected).isNotNull();
        return selected;
    }

    public static final class PluginRow extends AbstractTestElement {
        private static final PluginRowSchema SCHEMA = new PluginRowSchema();

        public PluginRow() {
        }

        PluginRow(String key, String payload) {
            setProperty("fixture.key", key);
            setProperty("fixture.payload", payload);
        }

        @Override
        public TestElementSchema getSchema() {
            return SCHEMA;
        }
    }

    public static final class PluginRowSchema extends TestElementSchema {
        PluginRowSchema() {
            stringDescriptor("key", "fixture.key");
            stringDescriptor("payload", "fixture.payload");
        }
    }

    public static class PluginRows extends AbstractTestElement {
        PluginRows() {
            setRows(Collections.<PluginRow>emptyList());
        }

        public CollectionProperty getRows() {
            return (CollectionProperty) getProperty("fixture.rows");
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }
    }

    public static final class MutatorOnlyRows extends AbstractTestElement {
        MutatorOnlyRows() {
            setRows(Collections.<PluginRow>emptyList());
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }
    }

    public static final class AccessorOnlyRows extends AbstractTestElement {
        AccessorOnlyRows() {
            setProperty(new CollectionProperty("fixture.rows", Collections.emptyList()));
        }

        public CollectionProperty getRows() {
            return (CollectionProperty) getProperty("fixture.rows");
        }
    }

    public static final class AlternateRow extends AbstractTestElement {
        public AlternateRow() {
        }
    }

    public static final class MismatchedRows extends AbstractTestElement {
        MismatchedRows() {
            setRows(Collections.<PluginRow>emptyList());
        }

        public List<AlternateRow> getRows() {
            return Collections.emptyList();
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }
    }

    public static final class OverloadedRows extends AbstractTestElement {
        OverloadedRows() {
            setRows(Collections.<PluginRow>emptyList());
        }

        public CollectionProperty getRows() {
            return (CollectionProperty) getProperty("fixture.rows");
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }

        public void setRows(ArrayList<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }
    }

    public static final class MultipleRelationshipRows extends AbstractTestElement {
        MultipleRelationshipRows() {
            setRows(Collections.<PluginRow>emptyList());
        }

        public List<PluginRow> getRows() {
            return pluginRows(this);
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }

        public List<PluginRow> getEntries() {
            return pluginRows(this);
        }

        public void setEntries(List<PluginRow> rows) {
            setRows(rows);
        }
    }

    public static final class UnrelatedStorageRows extends AbstractTestElement {
        private final CollectionProperty unrelated =
                new CollectionProperty("detached.rows", Collections.emptyList());

        UnrelatedStorageRows() {
            setRows(Collections.<PluginRow>emptyList());
        }

        public CollectionProperty getRows() {
            return unrelated;
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }
    }

    public static final class MismatchedStemRows extends AbstractTestElement {
        MismatchedStemRows() {
            setRows(Collections.<PluginRow>emptyList());
        }

        public CollectionProperty getEntries() {
            return (CollectionProperty) getProperty("fixture.rows");
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }
    }

    public static final class AmbiguousRows extends PluginRows {
        AmbiguousRows() {
            setProperty(new CollectionProperty("fixture.other", Collections.emptyList()));
        }

        public void setOtherRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.other", rows));
        }
    }

    public static final class InconsistentRows extends AbstractTestElement {
        InconsistentRows() {
            PluginRow first = new PluginRow("alpha", "one");
            PluginRow second = new PluginRow("beta", "two");
            second.setProperty("fixture.extra", "unexpected");
            setRows(Arrays.asList(first, second));
        }

        public void setRows(List<PluginRow> rows) {
            setProperty(new CollectionProperty("fixture.rows", rows));
        }
    }

    private static List<PluginRow> pluginRows(AbstractTestElement outer) {
        List<PluginRow> rows = new ArrayList<PluginRow>();
        for (org.apache.jmeter.testelement.property.JMeterProperty property
                : (CollectionProperty) outer.getProperty("fixture.rows")) {
            rows.add((PluginRow) property.getObjectValue());
        }
        return rows;
    }
}
