package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphOwnership;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.path.PropertyPath;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class PropertyGraphSystemIdentityCharacterizationTest {
    private static final String SYSTEM_REASON =
            "JMeter identity metadata is managed by the selected runtime";

    @Test
    void pinsRetainedNonWritableIdentityAndOrdinaryReadDenominatorBoundary() throws Exception {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.assertions.gui.AssertionGui");
        assertion.setProperty(TestElement.TEST_CLASS, ResponseAssertion.class.getName());
        assertion.setName("Todo 15 identity characterization");

        GraphSnapshot snapshot = new DefaultJMeterPropertyGraph().inspect(
                assertion, LocalPropertyGraphRuntimeContext.inProcess());
        GraphNode gui = snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("TestElement.gui_class"));
        GraphNode test = snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("TestElement.test_class"));

        assertThat(gui.value().scalarValue())
                .isEqualTo("org.apache.jmeter.assertions.gui.AssertionGui");
        assertThat(test.value().scalarValue()).isEqualTo(ResponseAssertion.class.getName());
        assertThat(gui.capability().ownership()).isEqualTo(GraphOwnership.SYSTEM);
        assertThat(test.capability().ownership()).isEqualTo(GraphOwnership.SYSTEM);
        assertThat(gui.capability().writable()).isFalse();
        assertThat(test.capability().writable()).isFalse();
        assertThat(gui.capability().reason()).contains(SYSTEM_REASON);
        assertThatThrownBy(() -> snapshot.resolveWritable(
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("TestElement.gui_class")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Property '[TestElement.gui_class]' is read-only: " + SYSTEM_REASON);

        long userEditableDenominator = snapshot.nodes().stream()
                .filter(node -> node.capability().ownership() == GraphOwnership.USER)
                .count();
        assertThat(userEditableDenominator).isEqualTo(snapshot.nodes().size() - 2L);

        LocalJMeterWorkerResult read = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.renderReadData(
                        fixture(), jmeterHome(), "1", "jmx_99fb165049e0", "ALL", "false"));
        assertThat(read.response().success()).as(read.response().toJsonLine()).isTrue();
        assertThat(read.response().payload()).doesNotContain(
                "TestElement\\.gui_class", "TestElement\\.test_class", "type: unsupported");
    }

    private static Path fixture() {
        try {
            return Paths.get(PropertyGraphSystemIdentityCharacterizationTest.class.getResource(
                    "/property-graph-conformance/response-assertion.jmx").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("fixture URI is invalid", exception);
        }
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }
}
