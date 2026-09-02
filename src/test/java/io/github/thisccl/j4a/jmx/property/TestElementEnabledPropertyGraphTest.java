package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathErrorCode;
import io.github.thisccl.j4a.path.PropertyPathResolutionException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestElementEnabledPropertyGraphTest {
    private static final PropertyPath ENABLED =
            io.github.thisccl.j4a.path.TestPropertyPaths.properties(TestElement.ENABLED);
    private final DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();

    @BeforeAll
    static void initializeSaveService() {
        new SaveServiceJmxLoader(io.github.thisccl.j4a.TestJMeterRuntime.home());
    }

    @Test
    void unrelatedUndiscoverableAbsentPropertyRemainsRejected() {
        GraphSnapshot snapshot = graph.inspect(new ConfigTestElement(), runtimeContext());

        assertThatThrownBy(() -> snapshot.resolve(
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.undiscoverable")))
                .isInstanceOf(PropertyPathResolutionException.class)
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.MISSING_PROPERTY);
    }

    @Test
    void absentStringAndBooleanStorageExposeOneCanonicalBooleanNode() {
        for (ConfigTestElement element : storageVariants()) {
            GraphSnapshot snapshot = graph.inspect(element, runtimeContext());
            List<GraphNode> enabledNodes = enabledNodes(snapshot);

            assertThat(enabledNodes).hasSize(1);
            GraphNode enabled = enabledNodes.get(0);
            assertThat(enabled.type()).isEqualTo(GraphType.BOOLEAN);
            assertThat(enabled.value().presence()).isEqualTo(GraphPresence.PRESENT);
            assertThat(enabled.value().propertyClass()).isEqualTo(BooleanProperty.class.getName());
            assertThat(enabled.value().scalarValue()).isEqualTo(Boolean.valueOf(element.isEnabled()));
            assertThat(enabled.capability().writable()).isTrue();
            assertThat(enabled.capability().ownership()).isEqualTo(GraphOwnership.USER);
            assertThat(enabled.capability().representationSource())
                    .isEqualTo(RepresentationSource.RUNTIME);
        }
    }

    @Test
    void booleanWriteUsesTheTestElementApiForEveryStorageState() {
        for (TrackingElement element : trackingStorageVariants()) {
            boolean requested = !element.isEnabled();
            GraphSnapshot snapshot = graph.inspect(element, runtimeContext());
            element.resetSetEnabledCalls();

            MutationReceipt receipt = graph.apply(element, snapshot, Collections.singletonList(
                    booleanWrite(requested)));

            assertThat(element.setEnabledCalls()).isEqualTo(1);
            assertThat(element.isEnabled()).isEqualTo(requested);
            assertThat(rawEnabledPropertyCount(element)).isEqualTo(1);
            assertThat(graph.project(element, receipt).values())
                    .containsExactlyElementsOf(receipt.writes());
        }
    }

    @Test
    void absentStringAndBooleanStorageRoundTripAsCanonicalBooleanState() throws Exception {
        for (ConfigTestElement element : storageVariants()) {
            boolean requested = !element.isEnabled();
            GraphSnapshot snapshot = graph.inspect(element, runtimeContext());
            MutationReceipt receipt = graph.apply(element, snapshot, Collections.singletonList(
                    booleanWrite(requested)));

            ConfigTestElement reloaded = reload(element);

            assertThat(reloaded.isEnabled()).isEqualTo(requested);
            GraphNode persisted = graph.inspect(reloaded, runtimeContext()).resolveWritable(ENABLED);
            assertThat(persisted.type()).isEqualTo(GraphType.BOOLEAN);
            assertThat(persisted.value().scalarValue()).isEqualTo(Boolean.valueOf(requested));
            assertThat(graph.project(reloaded, receipt).values())
                    .containsExactlyElementsOf(receipt.writes());
        }
    }

    private static PropertyWrite booleanWrite(boolean value) {
        return new PropertyWrite(ENABLED, GraphType.BOOLEAN, RecursiveValue.scalar(
                GraphType.BOOLEAN, BooleanProperty.class.getName(), Boolean.valueOf(value)));
    }

    private static List<GraphNode> enabledNodes(GraphSnapshot snapshot) {
        List<GraphNode> result = new ArrayList<GraphNode>();
        for (GraphNode node : snapshot.nodes()) {
            if (ENABLED.equals(node.path())) {
                result.add(node);
            }
        }
        return result;
    }

    private static List<ConfigTestElement> storageVariants() {
        ConfigTestElement absent = withIdentity(new ConfigTestElement());
        absent.removeProperty(TestElement.ENABLED);
        ConfigTestElement string = withIdentity(new ConfigTestElement());
        string.setProperty(new StringProperty(TestElement.ENABLED, "false"));
        ConfigTestElement bool = withIdentity(new ConfigTestElement());
        bool.setProperty(new BooleanProperty(TestElement.ENABLED, false));
        return java.util.Arrays.asList(absent, string, bool);
    }

    private static List<TrackingElement> trackingStorageVariants() {
        TrackingElement absent = new TrackingElement();
        absent.removeProperty(TestElement.ENABLED);
        TrackingElement string = new TrackingElement();
        string.setProperty(new StringProperty(TestElement.ENABLED, "false"));
        TrackingElement bool = new TrackingElement();
        bool.setProperty(new BooleanProperty(TestElement.ENABLED, false));
        return java.util.Arrays.asList(absent, string, bool);
    }

    private static <T extends ConfigTestElement> T withIdentity(T element) {
        element.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.config.gui.SimpleConfigGui");
        element.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
        return element;
    }

    private static int rawEnabledPropertyCount(TestElement element) {
        int count = 0;
        PropertyIterator properties = element.propertyIterator();
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            if (TestElement.ENABLED.equals(property.getName())) {
                count++;
            }
        }
        return count;
    }

    private static ConfigTestElement reload(ConfigTestElement element) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(element, output);
        return (ConfigTestElement) SaveService.loadElement(
                new ByteArrayInputStream(output.toByteArray()));
    }

    private static RuntimeContext runtimeContext() {
        return new RuntimeContext("enabled-test-worker", new RuntimeFingerprint(
                "/opt/jmeter-fixture/apache-jmeter-5.6.3",
                "5.6.3",
                Collections.<String, String>emptyMap()));
    }

    private static final class TrackingElement extends ConfigTestElement {
        private static final long serialVersionUID = 1L;
        private int setEnabledCalls;

        @Override
        public void setEnabled(boolean enabled) {
            setEnabledCalls++;
            super.setEnabled(enabled);
        }

        private int setEnabledCalls() {
            return setEnabledCalls;
        }

        private void resetSetEnabledCalls() {
            setEnabledCalls = 0;
        }
    }
}
