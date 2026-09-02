package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyPath;
import java.util.Arrays;
import java.util.Collections;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.Test;

class PropertyGraphMapCodecTest {
    private static final String INITIAL_PROJECTION =
            "org.apache.jmeter.testelement.property.MapProperty|qa.map|map\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|a|one\n"
            + "  org.apache.jmeter.testelement.property.TestElementProperty|b|"
            + "org.apache.jmeter.config.ConfigTestElement\n"
            + "    org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.test_class|org.apache.jmeter.config.ConfigTestElement\n"
            + "    org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.gui_class|todo7.fixture.NestedGui\n"
            + "    org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.name|Todo 7 nested\n"
            + "    org.apache.jmeter.testelement.property.BooleanProperty|"
            + "TestElement.enabled|true\n"
            + "    org.apache.jmeter.testelement.property.IntegerProperty|qa.count|7\n";
    private static final String RELOADED_PROJECTION =
            "org.apache.jmeter.testelement.property.MapProperty|qa.map|map\n"
            + "  org.apache.jmeter.testelement.property.StringProperty|a|one\n"
            + "  org.apache.jmeter.testelement.property.TestElementProperty|b|"
            + "org.apache.jmeter.config.ConfigTestElement\n"
            + "    org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.gui_class|todo7.fixture.NestedGui\n"
            + "    org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.test_class|org.apache.jmeter.config.ConfigTestElement\n"
            + "    org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.name|Todo 7 nested\n"
            + "    org.apache.jmeter.testelement.property.StringProperty|"
            + "TestElement.enabled|true\n"
            + "    org.apache.jmeter.testelement.property.IntegerProperty|qa.count|7\n";

    @Test
    void pinCurrentMapDiscoveryRoundTripAndWritableCapability() {
        MapProperty fixture = Todo7CodecTestSupport.mapFixture();
        String before = Todo7CodecTestSupport.propertyProjection(fixture);

        GraphNode node = Todo7CodecTestSupport.inspect(
                Todo7CodecTestSupport.outer(fixture))
                .resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.map"));
        Todo7CodecTestSupport.SavedElement saved =
                Todo7CodecTestSupport.saveRoundTrip(fixture);
        MapProperty loaded = (MapProperty) saved.loaded();

        assertThat(fixture.getClass()).isEqualTo(MapProperty.class);
        assertThat(fixture.getName()).isEqualTo("qa.map");
        assertThat(Todo7CodecTestSupport.mapKeys(fixture)).containsExactly("a", "b");
        assertThat(node.type()).isEqualTo(GraphType.MAP);
        assertThat(node.value().propertyClass())
                .isEqualTo(Todo7CodecTestSupport.MAP_PROPERTY);
        assertThat(node.value().entries()).extracting(entry -> entry.key().type())
                .containsExactly(GraphType.STRING, GraphType.STRING);
        assertThat(node.value().entries()).extracting(entry -> entry.key().value())
                .containsExactly("a", "b");
        assertThat(node.capability().writable()).isTrue();
        assertThat(node.capability().reason()).isEmpty();
        assertThat(Todo7CodecTestSupport.inspect(Todo7CodecTestSupport.outer(fixture))
                .resolveWritable(io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.map")).capability().writable())
                .isTrue();

        assertThat(loaded.getClass()).isEqualTo(MapProperty.class);
        assertThat(loaded.getName()).isEqualTo("qa.map");
        assertThat(Todo7CodecTestSupport.mapKeys(loaded)).containsExactly("a", "b");
        assertThat(before).isEqualTo(INITIAL_PROJECTION);
        assertThat(Todo7CodecTestSupport.propertyProjection(loaded))
                .isEqualTo(RELOADED_PROJECTION);
        assertThat(saved.xml()).contains(
                "<mapProp name=\"qa.map\">",
                "<stringProp name=\"a\">one</stringProp>",
                "<elementProp name=\"b\"");
        assertThat(saved.xmlSha256())
                .isEqualTo("0539c9d2e0578a1d4e66a8f4b410d25c9b8a8e6c79055f687c654e45430d447c");
        assertThat(Todo7CodecTestSupport.sha256(before))
                .isEqualTo("890b603eeb4e0acb9fea2f4c03132e61cbcbf1cecac72bc4dfedeb7f2baf18c3");
    }

    @Test
    void pinCurrentMapFixtureShapeIsExact() {
        RecursiveValue value = RuntimePropertyValueDiscovery.read(
                Todo7CodecTestSupport.mapFixture());

        assertThat(value.type()).isEqualTo(GraphType.MAP);
        assertThat(value.presence()).isEqualTo(GraphPresence.PRESENT);
        assertThat(value.entries()).hasSize(2);
        assertThat(value.entries().get(0).value().scalarValue()).isEqualTo("one");
        assertThat(value.entries().get(1).value().elementClass())
                .isEqualTo(Todo7CodecTestSupport.ELEMENT_CLASS);
        assertThat(value.entries().get(1).value().properties())
                .extracting(write -> write.property().segments().get(0).name())
                .containsExactly(
                        "TestElement.test_class",
                        "TestElement.gui_class",
                        "TestElement.name",
                        "TestElement.enabled",
                        "qa.count");
        assertThat(Arrays.asList(
                value.entries().get(0).value().propertyClass(),
                value.entries().get(1).value().propertyClass()))
                .containsExactly(
                        Todo7CodecTestSupport.STRING_PROPERTY,
                        Todo7CodecTestSupport.ELEMENT_PROPERTY);
        assertThat(Collections.singletonList(value.propertyClass()))
                .containsExactly(Todo7CodecTestSupport.MAP_PROPERTY);
    }

    @Test
    void readsAndMaterializesOrderedTypedKeysWithNestedValuesAndEmptyState() {
        MapProperty observed = Todo7CodecValues.typedMapFixture();
        MapPropertyCodec codec = new MapPropertyCodec();

        RecursiveValue read = codec.read(observed);
        RecursiveValue requested = RecursiveValue.map(
                Todo7CodecTestSupport.MAP_PROPERTY,
                Arrays.asList(
                        Todo7CodecValues.entry(GraphType.INT, Integer.valueOf(1),
                                Todo7CodecValues.scalar(GraphType.STRING,
                                        Todo7CodecTestSupport.STRING_PROPERTY, "uno")),
                        Todo7CodecValues.entry(GraphType.BOOLEAN, Boolean.TRUE,
                                Todo7CodecValues.scalar(GraphType.INT,
                                        Todo7CodecTestSupport.INTEGER_PROPERTY, 9)),
                        Todo7CodecValues.entry(GraphType.STRING, "nested",
                                Todo7CodecValues.requestedElement(11))));
        MapProperty changed = (MapProperty) codec.materialize(observed, requested).get();
        MapProperty empty = (MapProperty) codec.materialize(observed, RecursiveValue.map(
                Todo7CodecTestSupport.MAP_PROPERTY,
                Collections.<RecursiveValue.MapEntry>emptyList())).get();
        MapProperty reloaded = (MapProperty) Todo7CodecTestSupport
                .saveRoundTrip(changed).loaded();
        MapProperty emptyReloaded = (MapProperty) Todo7CodecTestSupport
                .saveRoundTrip(empty).loaded();

        assertThat(read.entries()).extracting(entry -> entry.key().value())
                .containsExactly("nested", "true", "1");
        assertThat(Todo7CodecTestSupport.mapKeys(changed))
                .containsExactly("1", "true", "nested");
        assertThat(changed.get("true").getClass()).isEqualTo(IntegerProperty.class);
        assertThat(changed.get("true").getIntValue()).isEqualTo(9);
        assertThat(changed.get("1").getClass()).isEqualTo(StringProperty.class);
        assertThat(changed.get("1").getStringValue()).isEqualTo("uno");
        TestElement nested = ((TestElementProperty) changed.get("nested")).getElement();
        assertThat(nested.getClass().getName()).isEqualTo(Todo7CodecTestSupport.ELEMENT_CLASS);
        assertThat(nested.getPropertyAsString(TestElement.GUI_CLASS))
                .isEqualTo(Todo7CodecTestSupport.GUI_CLASS);
        assertThat(nested.getPropertyAsInt("qa.count")).isEqualTo(11);
        assertThat(Todo7CodecTestSupport.mapKeys(reloaded))
                .containsExactly("1", "true", "nested");
        assertThat(reloaded.get("1").getClass()).isEqualTo(StringProperty.class);
        assertThat(reloaded.get("true").getClass()).isEqualTo(IntegerProperty.class);
        assertThat(reloaded.get("nested").getClass()).isEqualTo(TestElementProperty.class);
        assertThat(Todo7CodecTestSupport.mapKeys(empty)).isEmpty();
        assertThat(Todo7CodecTestSupport.mapKeys(emptyReloaded)).isEmpty();
        assertThat(empty.getClass()).isEqualTo(MapProperty.class);
        assertThat(empty.getName()).isEqualTo(observed.getName());
        assertThat(Todo7CodecTestSupport.propertyProjection(observed))
                .isEqualTo(Todo7CodecTestSupport.propertyProjection(
                        Todo7CodecValues.typedMapFixture()));
    }

    @Test
    void materializesKnownConcreteMapWithoutAnObservedInstance() {
        RecursiveValue requested = RecursiveValue.map(
                Todo7CodecTestSupport.MAP_PROPERTY,
                Arrays.asList(Todo7CodecValues.entry(GraphType.LONG, Long.valueOf(4L),
                        Todo7CodecValues.scalar(GraphType.STRING,
                                Todo7CodecTestSupport.STRING_PROPERTY, "four"))));

        MapProperty result = (MapProperty) new MapPropertyCodec()
                .materialize("qa.new", requested).get();

        assertThat(result.getClass()).isEqualTo(MapProperty.class);
        assertThat(result.getName()).isEqualTo("qa.new");
        assertThat(Todo7CodecTestSupport.mapKeys(result)).containsExactly("4");
        assertThat(result.get("4").getClass()).isEqualTo(StringProperty.class);
    }

    @Test
    void rejectsStorageDuplicateAndMalformedMapBeforeObservedMutation() {
        MapProperty observed = Todo7CodecValues.typedMapFixture();
        String before = Todo7CodecTestSupport.propertyProjection(observed);
        RecursiveValue collision = RecursiveValue.map(
                Todo7CodecTestSupport.MAP_PROPERTY,
                Arrays.asList(
                        Todo7CodecValues.entry(GraphType.STRING, "1",
                                Todo7CodecValues.scalar(GraphType.STRING,
                                        Todo7CodecTestSupport.STRING_PROPERTY, "one")),
                        Todo7CodecValues.entry(GraphType.INT, Integer.valueOf(1),
                                Todo7CodecValues.scalar(GraphType.STRING,
                                        Todo7CodecTestSupport.STRING_PROPERTY, "other"))));

        assertThatThrownBy(() -> new MapPropertyCodec().materialize(observed, collision))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .extracting("errorCode", "path", "reason")
                .containsExactly(
                        PropertyGraphRepresentationErrorCode.DUPLICATE_MAP_KEY,
                        "$.entries[1].key",
                        "typed key collides with storage key '1'");
        assertThatThrownBy(() -> new MapPropertyCodec().materialize(observed,
                RecursiveValue.map(Todo7CodecTestSupport.ELEMENT_PROPERTY,
                        Collections.<RecursiveValue.MapEntry>emptyList())))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.property_class: property class does not match observed map class");
        assertThat(Todo7CodecTestSupport.propertyProjection(observed)).isEqualTo(before);
    }

}
