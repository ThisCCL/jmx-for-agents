package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathErrorCode;
import io.github.thisccl.j4a.path.PropertyPathResolutionException;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.testelement.schema.PropertyDescriptor;
import org.apache.jmeter.timers.SyncTimer;
import org.junit.jupiter.api.Test;

class JMeterPropertyGraphDiscoveryTest {
    private static final String TEST_STRINGS = "Asserion.test_strings";
    private static final String SYSTEM_REASON =
            "JMeter identity metadata is managed by the selected runtime";

    @Test
    void pinExistingRuntimeIterationDescriptorInputsAndSystemIdentity() throws Exception {
        ResponseAssertion assertion = materializedResponseAssertion();

        assertThat(runtimeRows(assertion)).containsExactly(
                "Asserion.test_strings|CollectionProperty|[login failed, access denied]",
                "TestElement.test_class|StringProperty|org.apache.jmeter.assertions.ResponseAssertion",
                "TestElement.gui_class|StringProperty|org.apache.jmeter.assertions.gui.AssertionGui",
                "TestElement.name|StringProperty|Todo 5 Response Assertion",
                "TestElement.enabled|BooleanProperty|true");
        assertThat(collectionValues(assertion.getProperty(TEST_STRINGS)))
                .containsExactly("login failed", "access denied");
        assertThat(schemaRows(assertion)).containsExactly(
                "TestElement.name|StringPropertyDescriptor|<null>",
                "TestPlan.comments|StringPropertyDescriptor|<null>",
                "TestElement.gui_class|ClassPropertyDescriptor|<null>",
                "TestElement.test_class|ClassPropertyDescriptor|<null>",
                "TestElement.enabled|BooleanPropertyDescriptor|true",
                "Sample.scope|StringPropertyDescriptor|<null>");
        assertThat(TestBean.class.isAssignableFrom(assertion.getClass())).isFalse();
        assertThat(assertion.getPropertyAsString(TestElement.GUI_CLASS))
                .isEqualTo("org.apache.jmeter.assertions.gui.AssertionGui");
        assertThat(assertion.getPropertyAsString(TestElement.TEST_CLASS))
                .isEqualTo("org.apache.jmeter.assertions.ResponseAssertion");

        assertThat(testBeanRows()).containsExactly(
                "groupSize|int|setGroupSize",
                "timeoutInMs|long|setTimeoutInMs");
    }

    @Test
    void runtimePresenceWinsAndIdentityDriftDoesNotGainSemanticProvenance() {
        PropertyGraphDiscoveryFixtures.ConflictHeaderManager element =
                new PropertyGraphDiscoveryFixtures.ConflictHeaderManager();
        element.setProperty(new IntegerProperty("HeaderManager.headers", 41));

        GraphNode node = inspect(element).resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("HeaderManager.headers"));

        assertThat(node.type()).isEqualTo(GraphType.INT);
        assertThat(node.value().presence()).isEqualTo(GraphPresence.PRESENT);
        assertThat(node.value().propertyClass())
                .isEqualTo("org.apache.jmeter.testelement.property.IntegerProperty");
        assertThat(node.value().scalarValue()).isEqualTo(41);
        assertThat(node.capability().runtimeClassConstraint().propertyClass())
                .isEqualTo("org.apache.jmeter.testelement.property.IntegerProperty");
        assertThat(node.capability().representationSource()).isEqualTo(RepresentationSource.RUNTIME);
        assertThat(node.provenance()).containsExactly(
                RepresentationSource.RUNTIME,
                RepresentationSource.JMETER_SCHEMA,
                RepresentationSource.TEST_BEAN);
    }

    @Test
    void declaredAbsentSourcesAreReconstructableButUnknownStructuredNamesAreExcluded() {
        GraphNode schema = inspect(materializedResponseAssertion())
                .resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("TestPlan.comments"));
        assertAbsent(schema, GraphType.STRING,
                "org.apache.jmeter.testelement.property.StringProperty",
                RepresentationSource.JMETER_SCHEMA);

        GraphNode beanInfo = inspect(new SyncTimer()).resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("groupSize"));
        assertAbsent(beanInfo, GraphType.INT,
                "org.apache.jmeter.testelement.property.IntegerProperty",
                RepresentationSource.TEST_BEAN);

        GraphSnapshot malformed = inspect(new PropertyGraphDiscoveryFixtures.MalformedSchemaElement());
        assertAbsent(malformed.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.after")), GraphType.STRING,
                "org.apache.jmeter.testelement.property.StringProperty",
                RepresentationSource.JMETER_SCHEMA);
        assertThat(malformed.find(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.null"))).isEmpty();
        assertThat(malformed.find(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.invalid"))).isEmpty();

        GraphSnapshot adapterOnly = inspect(
                new PropertyGraphDiscoveryFixtures.AdapterIdentityConflictElement());
        assertThat(adapterOnly.find(io.github.thisccl.j4a.path.TestPropertyPaths.properties("HeaderManager.headers"))).isEmpty();
        assertThat(adapterOnly.find(io.github.thisccl.j4a.path.TestPropertyPaths.properties("not.declared.anywhere"))).isEmpty();
        assertThatThrownBy(() -> adapterOnly.resolve(
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("not.declared.anywhere")))
                .isInstanceOf(PropertyPathResolutionException.class)
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.MISSING_PROPERTY);
    }

    @Test
    void topLevelAndNestedIdentityRemainSystemOwnedAndCannotResolveForWrite() {
        ConfigTestElement outer = new ConfigTestElement();
        setIdentity(outer, "outer.Gui", ConfigTestElement.class.getName());
        TestPlan nested = new TestPlan();
        setIdentity(nested, "nested.Gui", TestPlan.class.getName());
        outer.setProperty(new TestElementProperty("nested.element", nested));
        List<String> before = runtimeRows(outer);

        GraphSnapshot snapshot = inspect(outer);
        assertSystem(snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("TestElement.gui_class")));
        assertSystem(snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("TestElement.test_class")));
        PropertyPath nestedGui = io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "nested.element", "TestElement.gui_class");
        PropertyPath nestedTest = io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "nested.element", "TestElement.test_class");
        assertSystem(snapshot.resolve(nestedGui));
        assertSystem(snapshot.resolve(nestedTest));
        assertThatThrownBy(() -> snapshot.resolveWritable(nestedGui))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Property '[nested.element, TestElement.gui_class]' is read-only: "
                        + SYSTEM_REASON);
        assertThat(runtimeRows(outer)).containsExactlyElementsOf(before);
    }

    @Test
    void responseAssertionCollectionRetainsRuntimeClassValueOrderAndProvenance() {
        ResponseAssertion assertion = materializedResponseAssertion();
        List<String> before = runtimeRows(assertion);

        GraphNode node = inspect(assertion)
                .resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("Asserion.test_strings"));

        assertThat(node.type()).isEqualTo(GraphType.COLLECTION);
        assertThat(node.value().presence()).isEqualTo(GraphPresence.PRESENT);
        assertThat(node.value().propertyClass())
                .isEqualTo("org.apache.jmeter.testelement.property.CollectionProperty");
        assertThat(node.value().items()).extracting(RecursiveValue::scalarValue)
                .containsExactly("login failed", "access denied");
        assertThat(node.value().items()).extracting(RecursiveValue::propertyClass)
                .containsOnly("org.apache.jmeter.testelement.property.StringProperty");
        assertThat(node.provenance()).containsExactly(RepresentationSource.RUNTIME);
        assertThat(runtimeRows(assertion)).containsExactlyElementsOf(before);
    }

    private static ResponseAssertion materializedResponseAssertion() {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setProperty(TestElement.TEST_CLASS, ResponseAssertion.class.getName());
        assertion.setProperty(
                TestElement.GUI_CLASS, "org.apache.jmeter.assertions.gui.AssertionGui");
        assertion.setName("Todo 5 Response Assertion");
        assertion.setEnabled(true);
        assertion.addTestString("login failed");
        assertion.addTestString("access denied");
        return assertion;
    }

    private static GraphSnapshot inspect(TestElement element) {
        Map<String, String> libraries = new LinkedHashMap<String, String>();
        libraries.put("lib/ApacheJMeter_core.jar", "todo5-runtime-fixture");
        RuntimeContext context = new RuntimeContext("todo5-worker", new RuntimeFingerprint(
                "/opt/jmeter-fixture/apache-jmeter-5.6.3", "5.6.3", libraries));
        return new DefaultJMeterPropertyGraph().inspect(element, context);
    }

    private static void assertAbsent(
            GraphNode node, GraphType type, String propertyClass, RepresentationSource source) {
        assertThat(node.type()).isEqualTo(type);
        assertThat(node.value().presence()).isEqualTo(GraphPresence.ABSENT);
        assertThat(node.value().propertyClass()).isEqualTo(propertyClass);
        assertThat(node.capability().runtimeClassConstraint().propertyClass()).isEqualTo(propertyClass);
        assertThat(node.provenance()).containsExactly(source);
    }

    private static void setIdentity(TestElement element, String guiClass, String testClass) {
        element.setProperty(TestElement.GUI_CLASS, guiClass);
        element.setProperty(TestElement.TEST_CLASS, testClass);
    }

    private static void assertSystem(GraphNode node) {
        assertThat(node.capability().ownership()).isEqualTo(GraphOwnership.SYSTEM);
        assertThat(node.capability().writable()).isFalse();
        assertThat(node.capability().reason()).contains(SYSTEM_REASON);
        assertThat(node.provenance()).startsWith(RepresentationSource.RUNTIME);
    }

    private static List<String> runtimeRows(TestElement element) {
        List<String> rows = new ArrayList<String>();
        PropertyIterator properties = element.propertyIterator();
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            rows.add(property.getName() + "|" + property.getClass().getSimpleName()
                    + "|" + property.getStringValue());
        }
        return rows;
    }

    private static List<String> collectionValues(JMeterProperty collection) {
        List<String> values = new ArrayList<String>();
        PropertyIterator properties = ((MultiProperty) collection).iterator();
        while (properties.hasNext()) {
            values.add(properties.next().getStringValue());
        }
        return values;
    }

    private static List<String> schemaRows(TestElement element) {
        List<String> rows = new ArrayList<String>();
        for (PropertyDescriptor<?, ?> descriptor : element.getSchema().getProperties().values()) {
            Object defaultValue = descriptor.getDefaultValue();
            rows.add(descriptor.getName() + "|" + descriptor.getClass().getSimpleName()
                    + "|" + (defaultValue == null ? "<null>" : defaultValue));
        }
        return rows;
    }

    private static List<String> testBeanRows() throws Exception {
        List<String> rows = new ArrayList<String>();
        for (java.beans.PropertyDescriptor descriptor : Introspector.getBeanInfo(
                SyncTimer.class, AbstractTestElement.class, Introspector.USE_ALL_BEANINFO)
                .getPropertyDescriptors()) {
            if (descriptor.getWriteMethod() != null) {
                rows.add(descriptor.getName() + "|" + descriptor.getPropertyType().getSimpleName()
                        + "|" + descriptor.getWriteMethod().getName());
            }
        }
        Collections.sort(rows);
        return rows;
    }
}
