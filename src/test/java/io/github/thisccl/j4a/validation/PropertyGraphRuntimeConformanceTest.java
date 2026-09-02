package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphPresence;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.GraphType;
import io.github.thisccl.j4a.jmx.property.MutationReceipt;
import io.github.thisccl.j4a.jmx.property.PropertyWrite;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.path.PropertyPath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.Test;

class PropertyGraphRuntimeConformanceTest {
    @Test
    void directCollectionAndMapScalarLeavesHaveDeterministicClassification() throws Exception {
        Path home = PropertyGraphInventorySupport.selectedHome();
        LocalJMeterWorkerRuntime.initialize(home);
        ConfigTestElement element = new ConfigTestElement();
        element.setProperty(new CollectionProperty("qa.collection", Arrays.<JMeterProperty>asList(
                new StringProperty("0", "value"))));
        Map<String, JMeterProperty> entries = new LinkedHashMap<String, JMeterProperty>();
        entries.put("key", new StringProperty("key", "value"));
        element.setProperty(new MapProperty("qa.map", entries));
        GraphSnapshot snapshot = new DefaultJMeterPropertyGraph().inspect(
                element, LocalPropertyGraphRuntimeContext.selected(home));

        assertThat(PropertyGraphInventoryWorker.classification(
                element, snapshot, snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.collection", Integer.valueOf(0)))))
                .isEqualTo("structured_scalar");
        assertThat(PropertyGraphInventoryWorker.classification(
                element, snapshot, snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.path(
                        io.github.thisccl.j4a.path.TestPropertyPaths.property("qa.map"),
                        io.github.thisccl.j4a.path.TestPropertyPaths.key("key")))))
                .isEqualTo("structured_scalar");
    }

    @Test
    void pinNestedFixtureCollectionIsWritableAndPersistsThroughSaveService() throws Exception {
        Path home = PropertyGraphInventorySupport.selectedHome();
        Path fixture = Paths.get("src", "test", "resources", "property-graph-conformance",
                "builtin-nested-element.jmx").toAbsolutePath().normalize();
        LocalJMeterWorkerRuntime.initialize(home);
        JmxTestPlan plan = LocalJMeterWorkerJmx.load(fixture, home);
        TestElement source = plan.depthFirstTestElements().get(0);
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        RuntimeContext context = LocalPropertyGraphRuntimeContext.selected(home);
        GraphSnapshot snapshot = graph.inspect(source, context);
        PropertyPath path = io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "qa.element", "Arguments.arguments");

        GraphNode observed = snapshot.resolveWritable(path);
        assertThat(observed.value().presence()).isEqualTo(GraphPresence.PRESENT);
        assertThat(observed.type()).isEqualTo(GraphType.COLLECTION);
        assertThat(observed.value().items()).hasSize(1);

        TestElement candidate = reload(source);
        PropertyWrite write = new PropertyWrite(path, observed.type(), observed.value());
        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(write));
        assertThat(graph.project(candidate, receipt).values())
                .containsExactlyElementsOf(receipt.writes());
        TestElement reloaded = reload(candidate);
        GraphNode persisted = graph.inspect(reloaded, context).resolveWritable(path);

        assertThat(persisted.type()).isEqualTo(GraphType.COLLECTION);
        assertThat(persisted.value()).isEqualTo(observed.value());
    }

    private static TestElement reload(TestElement element) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(element, output);
        return (TestElement) SaveService.loadElement(
                new ByteArrayInputStream(output.toByteArray()));
    }
}
