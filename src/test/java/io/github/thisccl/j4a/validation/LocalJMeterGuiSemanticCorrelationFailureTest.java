package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jorphan.gui.ObjectTableModel;
import org.apache.jorphan.reflect.Functor;
import io.github.thisccl.j4a.jmx.property.PropertyWrite;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowEvidence;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowWriter;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import org.junit.jupiter.api.Test;

class LocalJMeterGuiSemanticCorrelationFailureTest {
    @Test
    void independentlyCorrelatesMultipleTableModelsWithDifferentCustomRowClasses() {
        LocalJMeterGuiSemanticMetadata.Observation observation = observe(IndependentTablesGui.class);

        assertThat(observation.structuredRowConsumers()).extracting(
                LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::property,
                LocalJMeterGuiSemanticMetadata.StructuredRowConsumer::exactRowClass)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("fixture.first", CustomArgument.class.getName()),
                        org.assertj.core.groups.Tuple.tuple("fixture.second", AlternateArgument.class.getName()));
    }

    @Test
    void noOpAndMultiplePropertyProbesFailClosed() {
        LocalJMeterGuiSemanticMetadata.Observation noOp = observe(NoOpGui.class);
        LocalJMeterGuiSemanticMetadata.Observation ambiguous = observe(AmbiguousGui.class);

        assertFailure(noOp, LocalJMeterGuiSemanticMetadata.FailureReason.PROBE_NO_OP);
        assertFailure(ambiguous, LocalJMeterGuiSemanticMetadata.FailureReason.PROBE_AMBIGUOUS);
        assertThat(noOp.structuredRowConsumers()).isEmpty();
        assertThat(ambiguous.structuredRowConsumers()).isEmpty();
    }

    @Test
    void rowConstructorFailureDoesNotAuthorizeTheTableConsumer() {
        LocalJMeterGuiSemanticMetadata.Observation observation = observe(NoDefaultConstructorGui.class);

        assertFailure(observation, LocalJMeterGuiSemanticMetadata.FailureReason.ROW_CONSTRUCTOR);
        assertThat(observation.structuredRowConsumers()).isEmpty();
    }

    @Test
    void overlappingMultiSetterFootprintsAbstainFromAuthoringPlans() {
        LocalJMeterGuiSemanticMetadata.Observation observation = observe(OverlapGui.class);
        LocalJMeterGuiSemanticMetadata.StructuredRowConsumer consumer =
                observation.structuredRowConsumers().get(0);
        RuntimeStructuredRowEvidence evidence = consumer.evidence();
        ConfigTestElement target = new ConfigTestElement();
        Arguments targetRows = new Arguments();
        targetRows.addArgument(new Argument("observed", "", "="));
        target.setProperty(new TestElementProperty("fixture.rows", targetRows));
        org.apache.jmeter.testelement.property.JMeterProperty targetProperty =
                target.getPropertyOrNull("fixture.rows");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (String property : consumer.rowProperties()) {
            row.put(property, property.startsWith("qa.") ? Boolean.FALSE : "");
        }
        row.put("qa.first", Boolean.TRUE);
        row.put("qa.second", Boolean.FALSE);
        row.put("qa.shared", Boolean.TRUE);
        int callsBeforeWrite = OverlapArgument.setterCalls;

        java.util.Optional<PropertyWrite> write = new RuntimeStructuredRowWriter().prepare(
                target,
                new PropertyPath(Collections.singletonList(
                        PropertyPathSegment.property("fixture.rows"))),
                Collections.singletonList(row),
                LocalPropertyGraphRuntimeContext.inProcess(), evidence);

        assertThat(observation.structuredRowConsumers()).hasSize(1);
        assertThat(write).isPresent();
        assertThat(OverlapArgument.setterCalls).isEqualTo(callsBeforeWrite);
        assertThat(target.getPropertyOrNull("fixture.rows")).isSameAs(targetProperty);
    }

    private static LocalJMeterGuiSemanticMetadata.Observation observe(Class<?> guiClass) {
        return LocalJMeterGuiSemanticCorrelation.observe(
                guiClass.getName(), "5.6.3", LocalPropertyGraphRuntimeContext.inProcess());
    }

    private static void assertFailure(
            LocalJMeterGuiSemanticMetadata.Observation observation,
            LocalJMeterGuiSemanticMetadata.FailureReason reason) {
        assertThat(observation.failures()).extracting(LocalJMeterGuiSemanticMetadata.Failure::reason)
                .contains(reason);
    }

    public static final class CustomArgument extends Argument {
        public CustomArgument() {
        }

        public CustomArgument(String name, String value, String metadata) {
            super(name, value, metadata);
        }
    }

    public static final class AlternateArgument extends Argument {
        public AlternateArgument() {
        }

        public AlternateArgument(String name, String value, String metadata) {
            super(name, value, metadata);
        }
    }

    public static final class NoDefaultConstructorArgument extends Argument {
        public NoDefaultConstructorArgument(String name) {
            super(name, "", "=");
        }
    }

    public static final class OverlapArgument extends Argument {
        private static int setterCalls;

        public OverlapArgument() {
            setProperty(new BooleanProperty("qa.first", false));
            setProperty(new BooleanProperty("qa.second", false));
            setProperty(new BooleanProperty("qa.shared", false));
        }

        public boolean isFirstFlag() {
            return getPropertyAsBoolean("qa.first");
        }

        public boolean isSecondFlag() {
            return getPropertyAsBoolean("qa.second");
        }

        public void setFirstFlag(boolean value) {
            setterCalls++;
            setProperty("qa.first", value);
            setProperty("qa.shared", value);
        }

        public void setSecondFlag(boolean value) {
            setterCalls++;
            setProperty("qa.second", value);
            setProperty("qa.shared", value);
        }
    }

    public abstract static class FixtureGui extends AbstractConfigGui {
        final ArgumentsPanel first;

        FixtureGui(Class<? extends Argument> rowClass) {
            first = panel("first", rowClass);
        }

        @Override
        public String getLabelResource() {
            return getClass().getSimpleName();
        }

        @Override
        public TestElement createTestElement() {
            ConfigTestElement element = new ConfigTestElement();
            modifyTestElement(element);
            return element;
        }

        @Override
        public void clearGui() {
            super.clearGui();
            first.clearGui();
        }

        static ArgumentsPanel panel(String name, Class<? extends Argument> rowClass) {
            ObjectTableModel model = new ObjectTableModel(
                    new String[] {"name", "value", "metadata"}, rowClass,
                    new Functor[] {
                            new Functor("getName"), new Functor("getValue"), new Functor("getMetaData")},
                    new Functor[] {
                            new Functor("setName"), new Functor("setValue"), new Functor("setMetaData")},
                    new Class<?>[] {String.class, String.class, String.class});
            return new ArgumentsPanel(name, Color.WHITE, true, false, model, true,
                    values -> rowClass == NoDefaultConstructorArgument.class
                            ? new NoDefaultConstructorArgument(values[0])
                            : rowClass == AlternateArgument.class
                                    ? new AlternateArgument(values[0], values[1], values[2])
                                    : new CustomArgument(values[0], values[1], values[2]));
        }

        static void attach(TestElement element, String property, ArgumentsPanel panel) {
            element.setProperty(new TestElementProperty(property, panel.createTestElement()));
        }
    }

    public static final class IndependentTablesGui extends FixtureGui {
        private final ArgumentsPanel second = panel("second", AlternateArgument.class);

        public IndependentTablesGui() {
            super(CustomArgument.class);
        }

        @Override
        public void clearGui() {
            super.clearGui();
            second.clearGui();
        }

        @Override
        public void modifyTestElement(TestElement element) {
            attach(element, "fixture.first", first);
            attach(element, "fixture.second", second);
        }
    }

    public static final class NoOpGui extends FixtureGui {
        public NoOpGui() {
            super(CustomArgument.class);
        }

        @Override
        public void modifyTestElement(TestElement element) {
            element.setProperty(new TestElementProperty("fixture.rows", new Arguments()));
        }
    }

    public static final class AmbiguousGui extends FixtureGui {
        public AmbiguousGui() {
            super(CustomArgument.class);
        }

        @Override
        public void modifyTestElement(TestElement element) {
            attach(element, "fixture.first", first);
            attach(element, "fixture.second", first);
        }
    }

    public static final class NoDefaultConstructorGui extends FixtureGui {
        public NoDefaultConstructorGui() {
            super(NoDefaultConstructorArgument.class);
        }

        @Override
        public void modifyTestElement(TestElement element) {
            attach(element, "fixture.rows", first);
        }
    }

    public static final class OverlapGui extends AbstractConfigGui {
        private final ArgumentsPanel panel;

        public OverlapGui() {
            ObjectTableModel model = new ObjectTableModel(
                    new String[] {"name", "first", "second"}, OverlapArgument.class,
                    new Functor[] {
                            new Functor("getName"), new Functor("isFirstFlag"),
                            new Functor("isSecondFlag")},
                    new Functor[] {
                            new Functor("setName"), new Functor("setFirstFlag"),
                            new Functor("setSecondFlag")},
                    new Class<?>[] {String.class, Boolean.class, Boolean.class});
            panel = new ArgumentsPanel("overlap", Color.WHITE, true, false, model, true,
                    values -> new OverlapArgument());
        }

        @Override
        public String getLabelResource() {
            return "overlap";
        }

        @Override
        public TestElement createTestElement() {
            ConfigTestElement element = new ConfigTestElement();
            modifyTestElement(element);
            return element;
        }

        @Override
        public void clearGui() {
            super.clearGui();
            panel.clearGui();
        }

        @Override
        public void modifyTestElement(TestElement element) {
            FixtureGui.attach(element, "fixture.rows", panel);
        }
    }
}
