package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PropertyGraphProjectionTest {
    @BeforeAll
    static void selectSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void reloadProjectionMatchesEveryReceiptPathAndIgnoresUnrelatedDefaults() throws Exception {
        ConfigTestElement source = PropertyGraphMutationTest.fixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        MutationReceipt receipt = graph.apply(candidate, snapshot, Arrays.asList(
                PropertyGraphMutationTest.write("qa.scalar",
                        PropertyGraphMutationTest.string("projected")),
                new PropertyWrite(PropertyGraphMutationTest.path("qa.nested", "qa.count"),
                        GraphType.INT, RecursiveValue.scalar(GraphType.INT,
                                IntegerProperty.class.getName(), 88)),
                new PropertyWrite(PropertyGraphMutationTest.path("qa.collection"),
                        GraphType.COLLECTION, RecursiveValue.collection(
                                CollectionProperty.class.getName(), Arrays.asList(
                                        PropertyGraphMutationTest.string("same"),
                                        PropertyGraphMutationTest.string("same"),
                                        PropertyGraphMutationTest.string("tail"))))));
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        reloaded.setProperty(new StringProperty("qa.unrelated.default", "ignored"));

        VerificationProjection projection = graph.project(reloaded, receipt);

        assertThat(projection.runtimeContext()).isEqualTo(receipt.runtimeContext());
        assertThat(projection.values()).isEqualTo(receipt.writes());
        assertThat(projection.values()).extracting(value -> value.property().segments())
                .containsExactlyElementsOf(Arrays.asList(
                        PropertyGraphMutationTest.path("qa.scalar").segments(),
                        PropertyGraphMutationTest.path("qa.nested", "qa.count").segments(),
                        PropertyGraphMutationTest.path("qa.collection").segments()));
        assertThatThrownBy(() -> projection.values().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void oneNestedOrderMismatchRejectsTheWholeProjectionWithoutByteDrift() throws Exception {
        ConfigTestElement target = PropertyGraphMutationTest.fixture();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        RecursiveValue expectedOrder = RecursiveValue.collection(
                CollectionProperty.class.getName(), Arrays.asList(
                        PropertyGraphMutationTest.string("one"),
                        PropertyGraphMutationTest.string("two"),
                        PropertyGraphMutationTest.string("three")));
        MutationReceipt receipt = new MutationReceipt(
                Todo8OpaqueFixtures.runtimeContext(), Arrays.asList(
                        PropertyGraphMutationTest.write(
                                "qa.scalar", PropertyGraphMutationTest.string("before")),
                        new PropertyWrite(
                                PropertyGraphMutationTest.path("qa.nested", "qa.values"),
                                GraphType.COLLECTION, expectedOrder)));
        TestElementProperty nested = (TestElementProperty) target.getPropertyOrNull("qa.nested");
        nested.getElement().setProperty(new CollectionProperty("qa.values", Arrays.asList(
                new StringProperty("0", "one"),
                new StringProperty("1", "three"),
                new StringProperty("2", "two"))));
        byte[] before = Todo8OpaqueFixtures.saveElement(target);
        String beforeHash = Todo8OpaqueFixtures.sha256(before);

        assertThatThrownBy(() -> graph.project(target, receipt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection mismatch")
                .hasMessageContaining("[qa.nested, qa.values]");

        byte[] after = Todo8OpaqueFixtures.saveElement(target);
        assertThat(after).containsExactly(before);
        assertThat(Todo8OpaqueFixtures.sha256(after)).isEqualTo(beforeHash);
        System.out.println("ATOMIC_MISMATCH_TARGET_SHA256=" + beforeHash);
    }

    @Test
    void changedNestedContainerClassRejectsEqualWrittenLeafWithoutByteDrift() throws Exception {
        ConfigTestElement source = PropertyGraphMutationTest.fixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        PropertyWrite leafWrite = new PropertyWrite(
                PropertyGraphMutationTest.path("qa.nested", "qa.count"),
                GraphType.INT,
                RecursiveValue.scalar(GraphType.INT, IntegerProperty.class.getName(), 88));
        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(leafWrite));

        Arguments replacement = new Arguments();
        replacement.setProperty(TestElement.TEST_CLASS, Arguments.class.getName());
        replacement.setProperty(TestElement.GUI_CLASS, "todo9.projection.ReplacementGui");
        replacement.setProperty(new IntegerProperty("qa.count", 88));
        candidate.setProperty(new TestElementProperty("qa.nested", replacement));
        byte[] before = Todo8OpaqueFixtures.saveElement(candidate);
        String beforeHash = Todo8OpaqueFixtures.sha256(before);

        assertThatThrownBy(() -> graph.project(candidate, receipt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection mismatch")
                .hasMessageContaining("[qa.nested");

        byte[] after = Todo8OpaqueFixtures.saveElement(candidate);
        assertThat(after).containsExactly(before);
        assertThat(Todo8OpaqueFixtures.sha256(after)).isEqualTo(beforeHash);
    }

    @Test
    void classPresenceAndValueMismatchesAreRejectedAtTheirScalarArrayAddresses() throws Exception {
        ConfigTestElement target = PropertyGraphMutationTest.fixture();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();

        MutationReceipt wrongClass = receipt("qa.scalar", GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING,
                        IntegerProperty.class.getName(), "before"));
        assertThatThrownBy(() -> graph.project(target, wrongClass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[qa.scalar]");

        MutationReceipt wrongPresence = receipt("qa.scalar", GraphType.STRING,
                RecursiveValue.absent(GraphType.STRING, StringProperty.class.getName()));
        assertThatThrownBy(() -> graph.project(target, wrongPresence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[qa.scalar]");

        MutationReceipt wrongValue = receipt("qa.scalar", GraphType.STRING,
                PropertyGraphMutationTest.string("not-before"));
        assertThatThrownBy(() -> graph.project(target, wrongValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[qa.scalar]");
    }

    private static MutationReceipt receipt(
            String path, GraphType type, RecursiveValue value) throws Exception {
        return new MutationReceipt(Todo8OpaqueFixtures.runtimeContext(),
                Collections.singletonList(new PropertyWrite(
                        PropertyGraphMutationTest.path(path), type, value)));
    }
}
