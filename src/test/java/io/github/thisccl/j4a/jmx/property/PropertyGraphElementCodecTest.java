package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyPath;
import java.util.Arrays;
import java.util.Collections;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.Test;

class PropertyGraphElementCodecTest {
    private static final String INITIAL_PROJECTION =
            "org.apache.jmeter.testelement.property.TestElementProperty|qa.element|"
            + "org.apache.jmeter.config.ConfigTestElement\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.test_class|org.apache.jmeter.config.ConfigTestElement\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.gui_class|todo7.fixture.NestedGui\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.name|Todo 7 nested\n"
            + "  org.apache.jmeter.testelement.property.BooleanProperty|"
            + "TestElement.enabled|true\n"
            + "  org.apache.jmeter.testelement.property.IntegerProperty|qa.count|7\n";
    private static final String RELOADED_PROJECTION =
            "org.apache.jmeter.testelement.property.TestElementProperty|qa.element|"
            + "org.apache.jmeter.config.ConfigTestElement\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.gui_class|todo7.fixture.NestedGui\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.test_class|org.apache.jmeter.config.ConfigTestElement\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.name|Todo 7 nested\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.enabled|true\n"
            + "  org.apache.jmeter.testelement.property.IntegerProperty|qa.count|7\n";

    @Test
    void pinCurrentElementDiscoveryIdentityRoundTripAndWritableCapability() {
        TestElementProperty fixture = Todo7CodecTestSupport.elementFixture("qa.element");
        String before = Todo7CodecTestSupport.propertyProjection(fixture);

        GraphSnapshot snapshot = Todo7CodecTestSupport.inspect(
                Todo7CodecTestSupport.outer(fixture));
        GraphNode node = snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.element"));
        GraphNode gui = snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "qa.element", "TestElement.gui_class"));
        GraphNode test = snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties(
                "qa.element", "TestElement.test_class"));
        Todo7CodecTestSupport.SavedElement saved =
                Todo7CodecTestSupport.saveRoundTrip(fixture);
        TestElementProperty loaded = (TestElementProperty) saved.loaded();

        assertThat(fixture.getClass()).isEqualTo(TestElementProperty.class);
        assertThat(fixture.getName()).isEqualTo("qa.element");
        assertThat(node.type()).isEqualTo(GraphType.ELEMENT);
        assertThat(node.value().propertyClass())
                .isEqualTo(Todo7CodecTestSupport.ELEMENT_PROPERTY);
        assertThat(node.value().elementClass())
                .isEqualTo(Todo7CodecTestSupport.ELEMENT_CLASS);
        assertThat(gui.capability().ownership()).isEqualTo(GraphOwnership.SYSTEM);
        assertThat(test.capability().ownership()).isEqualTo(GraphOwnership.SYSTEM);
        assertThat(gui.capability().writable()).isFalse();
        assertThat(test.capability().writable()).isFalse();
        assertThat(node.capability().writable()).isTrue();
        assertThat(node.capability().reason()).isEmpty();
        assertThat(snapshot.resolveWritable(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.element"))).isSameAs(node);

        assertThat(loaded.getClass()).isEqualTo(TestElementProperty.class);
        assertThat(loaded.getName()).isEqualTo("qa.element");
        assertThat(loaded.getElement().getClass().getName())
                .isEqualTo(Todo7CodecTestSupport.ELEMENT_CLASS);
        assertThat(loaded.getElement().getPropertyAsString(TestElement.GUI_CLASS))
                .isEqualTo(Todo7CodecTestSupport.GUI_CLASS);
        assertThat(loaded.getElement().getPropertyAsString(TestElement.TEST_CLASS))
                .isEqualTo(Todo7CodecTestSupport.ELEMENT_CLASS);
        assertThat(before).isEqualTo(INITIAL_PROJECTION);
        assertThat(Todo7CodecTestSupport.propertyProjection(loaded))
                .isEqualTo(RELOADED_PROJECTION);
        assertThat(saved.xml()).contains(
                "<elementProp name=\"qa.element\"",
                "elementType=\"ConfigTestElement\"",
                "guiclass=\"todo7.fixture.NestedGui\"");
        assertThat(saved.xmlSha256())
                .isEqualTo("ce732bf4e484265b58a96f0ac72458cf17b77cde8d89bdc0bc33eb33091723c8");
        assertThat(Todo7CodecTestSupport.sha256(before))
                .isEqualTo("50a89da44278db7d6578cdeba2d736e9a42613fca00ee4393eee942202083a0e");
    }

    @Test
    void pinCurrentElementFixturePropertyOrderAndConcreteMetadata() {
        RecursiveValue value = RuntimePropertyValueDiscovery.read(
                Todo7CodecTestSupport.elementFixture("qa.element"));

        assertThat(value.type()).isEqualTo(GraphType.ELEMENT);
        assertThat(value.presence()).isEqualTo(GraphPresence.PRESENT);
        assertThat(value.propertyClass())
                .isEqualTo(Todo7CodecTestSupport.ELEMENT_PROPERTY);
        assertThat(value.elementClass()).isEqualTo(Todo7CodecTestSupport.ELEMENT_CLASS);
        assertThat(value.properties()).extracting(write -> write.property().segments().get(0).name())
                .containsExactly(
                        "TestElement.test_class",
                        "TestElement.gui_class",
                        "TestElement.name",
                        "TestElement.enabled",
                        "qa.count");
        assertThat(value.properties()).extracting(write -> write.value().propertyClass())
                .containsExactly(
                        Todo7CodecTestSupport.STRING_PROPERTY,
                        Todo7CodecTestSupport.STRING_PROPERTY,
                        Todo7CodecTestSupport.STRING_PROPERTY,
                        "org.apache.jmeter.testelement.property.BooleanProperty",
                        Todo7CodecTestSupport.INTEGER_PROPERTY);
    }

    @Test
    void readsAndMaterializesCompleteExactElementDocument() {
        TestElementProperty observed = Todo7CodecValues.elementWithMap();
        TestElementPropertyCodec codec = new TestElementPropertyCodec();

        RecursiveValue read = codec.read(observed);
        RecursiveValue requested = RecursiveValue.element(
                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                Todo7CodecTestSupport.ELEMENT_CLASS,
                Arrays.asList(
                        Todo7CodecValues.write("TestElement.name", GraphType.STRING,
                                RecursiveValue.scalar(GraphType.STRING,
                                        Todo7CodecTestSupport.STRING_PROPERTY, "Changed")),
                        Todo7CodecValues.write("TestElement.enabled", GraphType.BOOLEAN,
                                RecursiveValue.scalar(GraphType.BOOLEAN,
                                        "org.apache.jmeter.testelement.property.BooleanProperty",
                                        true)),
                        Todo7CodecValues.write("qa.count", GraphType.INT,
                                RecursiveValue.scalar(GraphType.INT,
                                        Todo7CodecTestSupport.INTEGER_PROPERTY, 12)),
                        Todo7CodecValues.write("qa.options", GraphType.MAP,
                                Todo7CodecValues.requestedOptions())));
        TestElementProperty changed = (TestElementProperty) codec
                .materialize(observed, requested).get();
        TestElementProperty reloaded = (TestElementProperty) Todo7CodecTestSupport
                .saveRoundTrip(changed).loaded();

        assertThat(read.elementClass()).isEqualTo(Todo7CodecTestSupport.ELEMENT_CLASS);
        assertThat(read.properties()).extracting(write -> write.value().propertyClass())
                .contains(Todo7CodecTestSupport.STRING_PROPERTY,
                        Todo7CodecTestSupport.INTEGER_PROPERTY,
                        Todo7CodecTestSupport.MAP_PROPERTY);
        assertThat(Todo7CodecValues.hasExactIdentity(changed.getElement())).isTrue();
        assertThat(changed.getElement().getName()).isEqualTo("Changed");
        assertThat(changed.getElement().getPropertyAsInt("qa.count")).isEqualTo(12);
        MapProperty options = (MapProperty) changed.getElement().getProperty("qa.options");
        assertThat(Todo7CodecTestSupport.mapKeys(options)).containsExactly("a", "b");
        assertThat(options.get("b").getIntValue()).isEqualTo(22);
        assertThat(Todo7CodecValues.hasExactIdentity(reloaded.getElement())).isTrue();
        assertThat(reloaded.getElement().getPropertyAsInt("qa.count")).isEqualTo(12);
        assertThat(Todo7CodecTestSupport.mapKeys((MapProperty)
                reloaded.getElement().getProperty("qa.options"))).containsExactly("a", "b");
        assertThat(Todo7CodecTestSupport.propertyProjection(observed))
                .isEqualTo(Todo7CodecTestSupport.propertyProjection(
                        Todo7CodecValues.elementWithMap()));
    }

    @Test
    void rejectsUnseenElementClassAndSystemIdentityWritesBeforeMutation() {
        TestElementProperty observed = Todo7CodecValues.elementWithMap();
        String before = Todo7CodecTestSupport.propertyProjection(observed);
        RecursiveValue unseen = RecursiveValue.element(
                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                "todo7.unseen.CallerSelectedElement",
                Collections.<PropertyWrite>emptyList());
        RecursiveValue identityWrite = RecursiveValue.element(
                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                Todo7CodecTestSupport.ELEMENT_CLASS,
                Arrays.asList(Todo7CodecValues.write(TestElement.GUI_CLASS, GraphType.STRING,
                        RecursiveValue.scalar(GraphType.STRING,
                                Todo7CodecTestSupport.STRING_PROPERTY, "caller.Gui"))));

        assertThatThrownBy(() -> new TestElementPropertyCodec().materialize(observed, unseen))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.element_class: element class does not match observed element class");
        assertThatThrownBy(() -> new TestElementPropertyCodec()
                .materialize(observed, identityWrite))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.properties[0].property: system identity property is read-only");
        assertThat(Todo7CodecTestSupport.propertyProjection(observed)).isEqualTo(before);
    }

    @Test
    void rejectsNestedIdentityWriteMalformedPathAndDuplicatePropertyBeforeMutation() {
        TestElementProperty observed = Todo7CodecTestSupport.elementFixture("qa.outer");
        observed.getElement().setProperty(Todo7CodecTestSupport.elementFixture("qa.child"));
        String before = Todo7CodecTestSupport.propertyProjection(observed);
        RecursiveValue nestedIdentity = RecursiveValue.element(
                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                Todo7CodecTestSupport.ELEMENT_CLASS,
                Arrays.asList(Todo7CodecValues.write("qa.child", GraphType.ELEMENT,
                        RecursiveValue.element(
                                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                                Todo7CodecTestSupport.ELEMENT_CLASS,
                                Arrays.asList(Todo7CodecValues.write(
                                        TestElement.TEST_CLASS, GraphType.STRING,
                                        RecursiveValue.scalar(GraphType.STRING,
                                                Todo7CodecTestSupport.STRING_PROPERTY,
                                                Todo7CodecTestSupport.ELEMENT_CLASS)))))));
        RecursiveValue malformed = RecursiveValue.element(
                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                Todo7CodecTestSupport.ELEMENT_CLASS,
                Arrays.asList(new PropertyWrite(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa", "child", Integer.valueOf(0)),
                        GraphType.INT, RecursiveValue.scalar(GraphType.INT,
                                Todo7CodecTestSupport.INTEGER_PROPERTY, 1))));
        PropertyWrite duplicate = Todo7CodecValues.write("qa.count", GraphType.INT,
                RecursiveValue.scalar(GraphType.INT,
                        Todo7CodecTestSupport.INTEGER_PROPERTY, 1));
        RecursiveValue duplicates = RecursiveValue.element(
                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                Todo7CodecTestSupport.ELEMENT_CLASS,
                Arrays.asList(duplicate, duplicate));

        assertThatThrownBy(() -> new TestElementPropertyCodec()
                .materialize(observed, nestedIdentity))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.properties[0].value.properties[0].property: system identity property is read-only");
        assertThatThrownBy(() -> new TestElementPropertyCodec().materialize(observed, malformed))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.properties[0].property: element property path requires one property segment");
        assertThatThrownBy(() -> new TestElementPropertyCodec().materialize(observed, duplicates))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.properties[1].property: duplicate element property 'qa.count'");
        assertThat(Todo7CodecTestSupport.propertyProjection(observed)).isEqualTo(before);
    }

}
