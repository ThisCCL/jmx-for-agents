package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NestedElementLeafMutationTest {
    private static final String CONTINUE_FOREVER = "LoopController.continue_forever";
    private static final PropertyPath CONTROLLER = new PropertyPath(Collections.singletonList(
            PropertyPathSegment.property(ThreadGroup.MAIN_CONTROLLER)));
    private static final PropertyPath LOOPS = new PropertyPath(Arrays.asList(
            PropertyPathSegment.property(ThreadGroup.MAIN_CONTROLLER),
            PropertyPathSegment.property(LoopController.LOOPS)));

    @BeforeAll
    static void selectSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void nestedLoopLeafRebuildsOnlyAncestorAndPreservesSiblingsClassesAndOrder()
            throws Exception {
        ThreadGroup source = threadGroup();
        ThreadGroup candidate = (ThreadGroup) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        TestElementProperty originalController = controller(candidate);
        List<String> outerOrder = propertyProjection(candidate);
        List<String> nestedOrder = propertyProjection(originalController.getElement());
        String guiClass = originalController.getElement()
                .getPropertyAsString(TestElement.GUI_CLASS);
        String testClass = originalController.getElement()
                .getPropertyAsString(TestElement.TEST_CLASS);
        RecursiveValue observed = snapshot.resolveWritable(LOOPS).value();

        MutationReceipt receipt = graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(LOOPS, observed.type(), RecursiveValue.scalar(
                        observed.type(), observed.propertyClass(), "10"))));
        graph.project(candidate, receipt);
        ThreadGroup reloaded = (ThreadGroup) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));

        assertThat((Object) controller(candidate)).isNotSameAs(originalController);
        assertThat(controller(candidate).getClass()).isEqualTo(originalController.getClass());
        assertThat(controller(candidate).getElement().getClass()).isEqualTo(LoopController.class);
        assertThat(controller(candidate).getElement().getPropertyAsString(LoopController.LOOPS))
                .isEqualTo("10");
        assertThat(controller(candidate).getElement()
                .getPropertyAsBoolean(CONTINUE_FOREVER)).isFalse();
        assertThat(propertyProjection(candidate)).containsExactlyElementsOf(outerOrder);
        assertThat(propertyProjection(controller(candidate).getElement()))
                .containsExactlyElementsOf(nestedOrder);
        assertThat(controller(candidate).getElement().getPropertyAsString(TestElement.GUI_CLASS))
                .isEqualTo(guiClass);
        assertThat(controller(candidate).getElement().getPropertyAsString(TestElement.TEST_CLASS))
                .isEqualTo(testClass);
        assertThat(graph.inspect(reloaded, Todo8OpaqueFixtures.runtimeContext())
                .resolve(LOOPS).value().scalarValue()).isEqualTo("10");
        assertThat(graph.inspect(source, Todo8OpaqueFixtures.runtimeContext())
                .resolve(LOOPS).value().scalarValue()).isEqualTo("1");
    }

    @Test
    void wholeElementRootRejectsIncompleteDocumentWithoutMutation() throws Exception {
        ThreadGroup source = threadGroup();
        ThreadGroup candidate = (ThreadGroup) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue observed = snapshot.resolveWritable(CONTROLLER).value();
        List<PropertyWrite> incomplete = new ArrayList<PropertyWrite>();
        for (PropertyWrite property : observed.properties()) {
            List<PropertyPathSegment> segments = property.property().segments();
            String name = segments.get(segments.size() - 1).name();
            if (!TestElement.GUI_CLASS.equals(name)
                    && !TestElement.TEST_CLASS.equals(name)
                    && !CONTINUE_FOREVER.equals(name)) {
                incomplete.add(property);
            }
        }
        RecursiveValue partial = RecursiveValue.element(
                observed.propertyClass(), observed.elementClass(), incomplete);
        byte[] before = Todo8OpaqueFixtures.saveElement(candidate);

        assertThatThrownBy(() -> graph.apply(candidate, snapshot, Collections.singletonList(
                new PropertyWrite(CONTROLLER, GraphType.ELEMENT, partial))))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessageContaining("complete element document")
                .hasMessageContaining(CONTINUE_FOREVER);
        assertThat(Todo8OpaqueFixtures.saveElement(candidate)).containsExactly(before);
    }

    @Test
    void duplicateAndAncestorDescendantWritesFailDeterministically() throws Exception {
        ThreadGroup source = threadGroup();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue leaf = snapshot.resolveWritable(LOOPS).value();
        RecursiveValue root = snapshot.resolveWritable(CONTROLLER).value();
        PropertyWrite leafWrite = new PropertyWrite(LOOPS, leaf.type(), RecursiveValue.scalar(
                leaf.type(), leaf.propertyClass(), "10"));
        PropertyWrite rootWrite = new PropertyWrite(CONTROLLER, GraphType.ELEMENT, root);

        assertThatThrownBy(() -> graph.apply((ThreadGroup) source.clone(), snapshot,
                Arrays.asList(leafWrite, leafWrite)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate property write: [ThreadGroup.main_controller, LoopController.loops]");
        assertThatThrownBy(() -> graph.apply((ThreadGroup) source.clone(), snapshot,
                Arrays.asList(rootWrite, leafWrite)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("overlapping property writes: [ThreadGroup.main_controller] and "
                        + "[ThreadGroup.main_controller, LoopController.loops]");
        assertThatThrownBy(() -> graph.apply((ThreadGroup) source.clone(), snapshot,
                Arrays.asList(leafWrite, rootWrite)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("overlapping property writes: [ThreadGroup.main_controller] and "
                        + "[ThreadGroup.main_controller, LoopController.loops]");
    }

    @Test
    void invalidLaterSiblingDiscardsEarlierLeafChangeInMemory() throws Exception {
        ThreadGroup source = threadGroup();
        ThreadGroup candidate = (ThreadGroup) source.clone();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(source, Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue observed = snapshot.resolveWritable(LOOPS).value();
        PropertyWrite valid = new PropertyWrite(LOOPS, observed.type(), RecursiveValue.scalar(
                observed.type(), observed.propertyClass(), "10"));
        PropertyPath invalidPath = new PropertyPath(Arrays.asList(
                PropertyPathSegment.property(ThreadGroup.MAIN_CONTROLLER),
                PropertyPathSegment.property("LoopController.missing")));
        PropertyWrite invalid = new PropertyWrite(invalidPath, observed.type(),
                RecursiveValue.scalar(observed.type(), observed.propertyClass(), "bad"));
        byte[] before = Todo8OpaqueFixtures.saveElement(candidate);

        assertThatThrownBy(() -> graph.apply(candidate, snapshot, Arrays.asList(valid, invalid)))
                .isInstanceOf(RuntimeException.class);
        assertThat(Todo8OpaqueFixtures.saveElement(candidate)).containsExactly(before);
        assertThat(graph.inspect(candidate, Todo8OpaqueFixtures.runtimeContext())
                .resolve(LOOPS).value().scalarValue()).isEqualTo("1");
    }

    private static ThreadGroup threadGroup() {
        JmxTestPlan plan = new SaveServiceJmxLoader(Todo8OpaqueFixtures.jmeterHome())
                .load(Todo8OpaqueFixtures.fixture("response-assertion.jmx"));
        return plan.depthFirstTestElements().stream()
                .filter(ThreadGroup.class::isInstance)
                .map(ThreadGroup.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Thread Group"));
    }

    private static TestElementProperty controller(ThreadGroup threadGroup) {
        return (TestElementProperty) threadGroup.getPropertyOrNull(ThreadGroup.MAIN_CONTROLLER);
    }

    private static List<String> propertyProjection(TestElement element) {
        List<String> projection = new ArrayList<String>();
        PropertyIterator iterator = element.propertyIterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            projection.add(property.getName() + "|" + property.getClass().getName());
        }
        return projection;
    }
}
