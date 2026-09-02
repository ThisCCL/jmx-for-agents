package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.apache.jmeter.gui.Binding;
import org.apache.jmeter.gui.BindingGroup;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.schema.BaseTestElementSchema;
import org.apache.jmeter.testelement.schema.IntegerPropertyDescriptor;
import org.apache.jmeter.testelement.schema.PropertyDescriptor;
import org.apache.jmeter.testelement.schema.StringPropertyDescriptor;
import org.apache.jorphan.gui.ObjectTableModel;
import org.apache.jorphan.reflect.Functor;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.junit.jupiter.api.Test;

class LocalJMeterGuiSemanticTraversalTest {
    @Test
    void correlatesHttpArgumentConsumerToItsOuterProperty() {
        LocalJMeterGuiSemanticMetadata.Observation observation =
                LocalJMeterGuiSemanticCorrelation.observe(
                        HttpTestSampleGui.class.getName(),
                        "5.6.3", LocalPropertyGraphRuntimeContext.inProcess());

        assertThat(observation.structuredRowConsumers())
                .as(observation.failures().stream()
                        .map(failure -> failure.reason() + ":" + failure.detail())
                        .collect(java.util.stream.Collectors.toList()).toString())
                .anySatisfy(consumer -> assertThat(consumer)
                        .extracting(
                                LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::property,
                                LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::exactRowClass)
                        .containsExactly(
                                "HTTPsampler.Arguments",
                                "org.apache.jmeter.protocol.http.util.HTTPArgument"));
        assertThat(observation.failures())
                .extracting(LocalJMeterGuiSemanticMetadata.Failure::reason)
                .contains(LocalJMeterGuiSemanticMetadata.FailureReason.PROBE_NO_OP);
    }

    @Test
    void correlatesOrdinaryArgumentConsumerWithoutPromotingAnotherRowFamily() {
        LocalJMeterGuiSemanticMetadata.Observation observation =
                LocalJMeterGuiSemanticCorrelation.observe(
                        ArgumentsPanel.class.getName(),
                        "5.6.3", LocalPropertyGraphRuntimeContext.inProcess());

        assertThat(observation.structuredRowConsumers())
                .singleElement()
                .satisfies(consumer -> assertThat(consumer)
                        .extracting(
                                LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::property,
                                LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::exactRowClass,
                                LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::reconstructionShape)
                        .containsExactly(
                                "Arguments.arguments",
                                "org.apache.jmeter.config.Argument",
                                "direct"));
    }

    @Test
    void selectedRuntimeEntryRetainsCorrelatedHttpArgumentEvidence() throws Exception {
        java.nio.file.Path home = io.github.thisccl.j4a.TestJMeterRuntime.home();
        LocalJMeterWorkerRuntime.initialize(home);
        LocalJMeterMenuRegistry.Entry entry = LocalJMeterMenuRegistry.current()
                .resolve("org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui").get();

        LocalJMeterGuiSemanticMetadata.Observation observation = LocalComponentDiscovery.semanticMetadata(
                entry, LocalPropertyGraphRuntimeContext.selected(home));

        assertThat(observation.structuredRowConsumers())
                .as(observation.failures().stream()
                        .map(failure -> failure.reason() + ":" + failure.detail())
                        .collect(java.util.stream.Collectors.toList()).toString())
                .extracting(LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::property)
                .contains("HTTPsampler.Arguments");
        assertThat(observation.structuredRowConsumers())
                .filteredOn(consumer -> "HTTPsampler.Arguments".equals(consumer.property()))
                .allSatisfy(consumer -> assertThat(consumer.evidence()).isNotNull());
    }

    @Test
    void traversesInheritedNestedBindingsByIdentityAndDeduplicatesDescriptors() {
        StringPropertyDescriptor<BaseTestElementSchema> descriptor = stringDescriptor("qa.scalar", "fallback");
        BindingGroup inherited = new BindingGroup(Arrays.<Binding>asList(
                new DescriptorBinding(descriptor), new DescriptorBinding(descriptor)));
        NestedRoot root = new NestedRoot(inherited);
        root.nested.back = root;

        LocalJMeterGuiSemanticMetadata.Observation observation = observe(root, normalBudget());

        assertThat(observation.runtimeProven()).isTrue();
        assertThat(observation.scalarDescriptors()).extracting(
                LocalJMeterGuiSemanticMetadata.ScalarDescriptor::property)
                .containsExactly("qa.scalar");
        assertThat(observation.scalarDescriptors().get(0).defaultValue()).isEqualTo("fallback");
        assertThatThrownBy(() -> observation.scalarDescriptors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observation.failures().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(observation.status()).isEqualTo(LocalJMeterGuiSemanticMetadata.SourceStatus.RUNTIME_PROVEN);
    }

    @Test
    void workerOwnedStructuredEvidenceRecordIsImmutableWithoutExposingRuntimeObjects() {
        LocalJMeterGuiSemanticMetadata.StructuredRowConsumer row =
                new LocalJMeterGuiSemanticMetadata.StructuredRowConsumer(
                        "qa.rows", "example.ExactRow", Arrays.asList("name", "value"), "direct");
        LocalJMeterGuiSemanticMetadata.Observation observation =
                new LocalJMeterGuiSemanticMetadata.Observation(
                        Collections.<LocalJMeterGuiSemanticMetadata.ScalarDescriptor>emptyList(),
                        Collections.singletonList(row),
                        Collections.<LocalJMeterGuiSemanticMetadata.Failure>emptyList(),
                        new LocalJMeterGuiSemanticMetadata.Stats(0, 0, 0, 0, 0, 0L));

        assertThat(row.property()).isEqualTo("qa.rows");
        assertThat(row.exactRowClass()).isEqualTo("example.ExactRow");
        assertThat(row.rowProperties()).containsExactly("name", "value");
        assertThat(row.reconstructionShape()).isEqualTo("direct");
        assertThatThrownBy(() -> row.rowProperties().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observation.structuredRowConsumers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unsupportedVersionAndOpaqueEvidenceFailSoftWithoutInventingProperties() {
        LocalJMeterGuiSemanticMetadata.Observation unsupported = LocalJMeterGuiSemanticTraversal.observe(
                new NestedRoot(new BindingGroup()), "5.7.0");
        LocalJMeterGuiSemanticMetadata.Observation opaque = observe(
                new NestedRoot(new BindingGroup(Collections.<Binding>singletonList(new OpaqueBinding()))),
                normalBudget());
        LocalJMeterGuiSemanticMetadata.Observation labelOnly = observe(
                new LabelOnlyRoot("Domain"), normalBudget());

        assertFailure(unsupported, LocalJMeterGuiSemanticMetadata.FailureReason.UNSUPPORTED_VERSION);
        assertFailure(opaque, LocalJMeterGuiSemanticMetadata.FailureReason.UNSUPPORTED_BINDING);
        assertThat(unsupported.scalarDescriptors()).isEmpty();
        assertThat(opaque.scalarDescriptors()).isEmpty();
        assertThat(labelOnly.runtimeProven()).isTrue();
        assertThat(labelOnly.scalarDescriptors()).isEmpty();
    }

    @Test
    void foreignRootAndNonJMeterDescendantFailClosed() {
        LocalJMeterGuiSemanticMetadata.Observation foreign =
                LocalJMeterGuiSemanticTraversal.observe(new Object(), "5.6.3");
        LocalJMeterGuiSemanticMetadata.Observation descendant = observe(
                new ForeignDescendantRoot(new AtomicReference<Object>(
                        new DescriptorBinding(stringDescriptor("qa.hidden", null)))),
                normalBudget());

        assertFailure(foreign, LocalJMeterGuiSemanticMetadata.FailureReason.FOREIGN_CLASSLOADER);
        assertThat(descendant.scalarDescriptors()).isEmpty();
        assertThat(descendant.stats().visitedObjects()).isEqualTo(2);
    }

    @Test
    void conflictingDuplicatesAndInaccessibleFieldsRemainUnavailable() {
        BindingGroup conflicting = new BindingGroup(Arrays.<Binding>asList(
                new DescriptorBinding(stringDescriptor("qa.same", null)),
                new DescriptorBinding(new IntegerPropertyDescriptor<BaseTestElementSchema>(
                        "same", "qa.same", Integer.valueOf(1))),
                new DescriptorBinding(stringDescriptor("qa.same", null))));
        LocalJMeterGuiSemanticMetadata.Observation duplicate = observe(
                new NestedRoot(conflicting), normalBudget());
        LocalJMeterGuiSemanticMetadata.Observation inaccessible = LocalJMeterGuiSemanticTraversal.observe(
                new LabelOnlyRoot("opaque"), "5.6.3", normalBudget(),
                new LocalJMeterGuiSemanticTraversal.FieldReader() {
                    @Override
                    public Object read(Field field, Object owner) throws IllegalAccessException {
                        throw new IllegalAccessException("denied");
                    }
                }, System::nanoTime);

        assertFailure(duplicate, LocalJMeterGuiSemanticMetadata.FailureReason.CONFLICTING_DESCRIPTOR);
        assertThat(duplicate.scalarDescriptors()).isEmpty();
        assertFailure(inaccessible, LocalJMeterGuiSemanticMetadata.FailureReason.INACCESSIBLE_FIELD);
        assertThat(inaccessible.scalarDescriptors()).isEmpty();
    }

    @Test
    void eachDeterministicTraversalBudgetStopsSoftly() {
        assertBudgetFailure(new NestedRoot(group("qa.depth")),
                new BudgetValues(0, 100, 100, 100, 100, 100, Long.MAX_VALUE),
                LocalJMeterGuiSemanticMetadata.FailureReason.DEPTH_BUDGET);
        assertBudgetFailure(new NestedRoot(group("qa.objects")),
                new BudgetValues(16, 1, 100, 100, 100, 100, Long.MAX_VALUE),
                LocalJMeterGuiSemanticMetadata.FailureReason.OBJECT_BUDGET);
        assertBudgetFailure(new NestedRoot(group("qa.fields")),
                new BudgetValues(16, 100, 0, 100, 100, 100, Long.MAX_VALUE),
                LocalJMeterGuiSemanticMetadata.FailureReason.FIELD_BUDGET);
        assertBudgetFailure(new NestedRoot(group("qa.descriptors")),
                new BudgetValues(16, 100, 100, 0, 100, 100, Long.MAX_VALUE),
                LocalJMeterGuiSemanticMetadata.FailureReason.DESCRIPTOR_BUDGET);
        assertBudgetFailure(new TableRoot(tableModel()),
                new BudgetValues(16, 100, 100, 100, 0, 100, Long.MAX_VALUE),
                LocalJMeterGuiSemanticMetadata.FailureReason.TABLE_CANDIDATE_BUDGET);
        assertBudgetFailure(new NestedRoot(group("qa.output")),
                new BudgetValues(16, 100, 100, 100, 100, 0, Long.MAX_VALUE),
                LocalJMeterGuiSemanticMetadata.FailureReason.OUTPUT_BUDGET);

        final AtomicLong clock = new AtomicLong();
        LongSupplier elapsedClock = new LongSupplier() {
            @Override
            public long getAsLong() {
                return clock.getAndAdd(2L);
            }
        };
        LocalJMeterGuiSemanticMetadata.Observation elapsed = LocalJMeterGuiSemanticTraversal.observe(
                new NestedRoot(group("qa.elapsed")), "5.6.3",
                new LocalJMeterGuiSemanticMetadata.Budget(16, 100, 100, 100, 100, 100, 1L),
                reflectiveReader(), elapsedClock);
        assertFailure(elapsed, LocalJMeterGuiSemanticMetadata.FailureReason.ELAPSED_TIME_BUDGET);
    }

    private static LocalJMeterGuiSemanticMetadata.Observation observe(
            Object root, LocalJMeterGuiSemanticMetadata.Budget budget) {
        return LocalJMeterGuiSemanticTraversal.observe(
                root, "5.6.3", budget, reflectiveReader(), System::nanoTime);
    }

    private static LocalJMeterGuiSemanticTraversal.FieldReader reflectiveReader() {
        return new LocalJMeterGuiSemanticTraversal.FieldReader() {
            @Override
            public Object read(Field field, Object owner) throws IllegalAccessException {
                if (!field.isAccessible()) field.setAccessible(true);
                return field.get(owner);
            }
        };
    }

    private static void assertBudgetFailure(
            Object root, BudgetValues values, LocalJMeterGuiSemanticMetadata.FailureReason reason) {
        LocalJMeterGuiSemanticMetadata.Budget budget = new LocalJMeterGuiSemanticMetadata.Budget(
                values.depth, values.objects, values.fields, values.descriptors,
                values.tables, values.output, values.elapsed);
        assertFailure(observe(root, budget), reason);
    }

    private static void assertFailure(
            LocalJMeterGuiSemanticMetadata.Observation observation,
            LocalJMeterGuiSemanticMetadata.FailureReason reason) {
        assertThat(observation.runtimeProven()).isFalse();
        assertThat(observation.failures()).extracting(LocalJMeterGuiSemanticMetadata.Failure::reason)
                .contains(reason);
    }

    private static BindingGroup group(String property) {
        return new BindingGroup(Collections.<Binding>singletonList(
                new DescriptorBinding(stringDescriptor(property, null))));
    }

    private static StringPropertyDescriptor<BaseTestElementSchema> stringDescriptor(
            String property, String defaultValue) {
        return new StringPropertyDescriptor<BaseTestElementSchema>(
                property.substring(property.lastIndexOf('.') + 1), property, defaultValue);
    }

    private static ObjectTableModel tableModel() {
        return new ObjectTableModel(new String[0], Object.class, new Functor[0], new Functor[0], new Class<?>[0]);
    }

    private static class InheritedRoot {
        private final BindingGroup inherited;

        private InheritedRoot(BindingGroup inherited) {
            this.inherited = inherited;
        }
    }

    private static final class NestedRoot extends InheritedRoot {
        private final Nested nested = new Nested();

        private NestedRoot(BindingGroup inherited) {
            super(inherited);
        }
    }

    private static final class Nested {
        private Object back;
    }

    private static final class LabelOnlyRoot {
        private final String visibleLabel;

        private LabelOnlyRoot(String visibleLabel) {
            this.visibleLabel = visibleLabel;
        }
    }

    private static final class ForeignDescendantRoot {
        private final AtomicReference<Object> foreign;

        private ForeignDescendantRoot(AtomicReference<Object> foreign) {
            this.foreign = foreign;
        }
    }

    private static final class TableRoot {
        private final ObjectTableModel tableModel;

        private TableRoot(ObjectTableModel tableModel) {
            this.tableModel = tableModel;
        }
    }

    private static final class DescriptorBinding implements Binding {
        private final PropertyDescriptor<?, ?> descriptor;

        private DescriptorBinding(PropertyDescriptor<?, ?> descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public void updateElement(TestElement testElement) {
        }

        @Override
        public void updateUi(TestElement testElement) {
        }
    }

    private static final class OpaqueBinding implements Binding {
        @Override
        public void updateElement(TestElement testElement) {
        }

        @Override
        public void updateUi(TestElement testElement) {
        }
    }

    private static final class BudgetValues {
        private final int depth;
        private final int objects;
        private final int fields;
        private final int descriptors;
        private final int tables;
        private final int output;
        private final long elapsed;

        private BudgetValues(int depth, int objects, int fields, int descriptors,
                int tables, int output, long elapsed) {
            this.depth = depth;
            this.objects = objects;
            this.fields = fields;
            this.descriptors = descriptors;
            this.tables = tables;
            this.output = output;
            this.elapsed = elapsed;
        }
    }

    private static LocalJMeterGuiSemanticMetadata.Budget normalBudget() {
        return new LocalJMeterGuiSemanticMetadata.Budget(16, 100, 100, 100, 100, 100, Long.MAX_VALUE);
    }
}
