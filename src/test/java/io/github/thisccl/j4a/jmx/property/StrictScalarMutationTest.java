package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyPath;
import java.util.Arrays;
import java.util.Collections;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StrictScalarMutationTest {
    @BeforeAll
    static void selectSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void exactFloatAndDoubleWritesReloadWithTheirConcreteClasses() throws Exception {
        ConfigTestElement source = fixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());

        MutationReceipt receipt = graph.apply(candidate, snapshot, Arrays.asList(
                write("qa.float", GraphType.FLOAT, RecursiveValue.scalar(
                        GraphType.FLOAT, FloatProperty.class.getName(), Float.valueOf(2.5F))),
                write("qa.double", GraphType.DOUBLE, RecursiveValue.scalar(
                        GraphType.DOUBLE, DoubleProperty.class.getName(), Double.valueOf(3.75D)))));
        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        VerificationProjection projection = graph.project(reloaded, receipt);

        assertThat(reloaded.getPropertyOrNull("qa.float")).isExactlyInstanceOf(FloatProperty.class);
        assertThat(reloaded.getPropertyOrNull("qa.double")).isExactlyInstanceOf(DoubleProperty.class);
        assertThat(projection.values()).extracting(PropertyWrite::type)
                .containsExactly(GraphType.FLOAT, GraphType.DOUBLE);
    }

    @Test
    void crossTypeAndStringCoercionFailuresLeaveCandidateAndSourceUnchanged() throws Exception {
        ConfigTestElement source = fixture();
        ConfigTestElement candidate = (ConfigTestElement) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        byte[] sourceBefore = Todo8OpaqueFixtures.saveElement(source);
        byte[] candidateBefore = Todo8OpaqueFixtures.saveElement(candidate);

        assertThatThrownBy(() -> graph.apply(candidate, snapshot, Collections.singletonList(
                write("qa.float", GraphType.DOUBLE, RecursiveValue.scalar(
                        GraphType.DOUBLE, DoubleProperty.class.getName(), Double.valueOf(2.5D))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[qa.float]");
        assertThatThrownBy(() -> graph.apply(candidate, snapshot, Collections.singletonList(
                write("qa.string", GraphType.INT, RecursiveValue.scalar(
                        GraphType.INT,
                        org.apache.jmeter.testelement.property.IntegerProperty.class.getName(),
                        Integer.valueOf(100))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[qa.string]");
        assertThat(Todo8OpaqueFixtures.saveElement(source)).containsExactly(sourceBefore);
        assertThat(Todo8OpaqueFixtures.saveElement(candidate)).containsExactly(candidateBefore);
    }

    private static ConfigTestElement fixture() {
        ConfigTestElement fixture = new ConfigTestElement();
        fixture.setProperty(new StringProperty("qa.string", "before"));
        fixture.setProperty(new FloatProperty("qa.float", 1.25F));
        fixture.setProperty(new DoubleProperty("qa.double", 1.5D));
        fixture.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.config.gui.SimpleConfigGui");
        fixture.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
        return fixture;
    }

    private static PropertyWrite write(
            String property,
            GraphType type,
            RecursiveValue value) {
        return new PropertyWrite(
                io.github.thisccl.j4a.path.TestPropertyPaths.properties(property),
                type,
                value);
    }
}
