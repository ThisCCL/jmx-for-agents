package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.Test;

class PropertyGraphCollectionCodecTest {
    @Test
    void pinResponseAssertionRuntimeCollectionNameClassChildrenAndOrder() {
        // Given
        ResponseAssertion assertion = Todo6CodecFixtures.responseAssertion(
                "login failed", "login failed", "access denied");

        // When
        JMeterProperty property = assertion.getPropertyOrNull(Todo6CodecFixtures.TEST_STRINGS);
        List<JMeterProperty> items = Todo6CodecFixtures.items(property);
        RecursiveValue discovered = RuntimePropertyValueDiscovery.read(property);

        // Then
        assertThat(property.getName()).isEqualTo(Todo6CodecFixtures.TEST_STRINGS);
        assertThat(property.getClass().getName()).isEqualTo(Todo6CodecFixtures.COLLECTION_CLASS);
        assertThat(items.stream().map(PropertyGraphCollectionCodecTest::itemRow)
                .collect(Collectors.toList())).containsExactly(
                        "-623340332|org.apache.jmeter.testelement.property.StringProperty|login failed",
                        "-623340332|org.apache.jmeter.testelement.property.StringProperty|login failed",
                        "-522775081|org.apache.jmeter.testelement.property.StringProperty|access denied");
        assertThat(discovered.type()).isEqualTo(GraphType.COLLECTION);
        assertThat(discovered.presence()).isEqualTo(GraphPresence.PRESENT);
        assertThat(discovered.propertyClass()).isEqualTo(Todo6CodecFixtures.COLLECTION_CLASS);
        assertThat(discovered.items()).extracting(RecursiveValue::scalarValue)
                .containsExactly("login failed", "login failed", "access denied");
        assertThat(discovered.items()).extracting(RecursiveValue::propertyClass)
                .containsOnly(Todo6CodecFixtures.STRING_CLASS);
    }

    @Test
    void materializesOrderedDuplicatesWithExactChildNamesAndClasses() {
        // Given
        CollectionPropertyCodec codec = new CollectionPropertyCodec();
        RecursiveValue requested = RecursiveValue.collection(
                Todo6CodecFixtures.COLLECTION_CLASS,
                Arrays.asList(string("login failed"), string("login failed"), string("access denied")));

        // When
        JMeterProperty property = codec.materialize(Todo6CodecFixtures.TEST_STRINGS, requested).get();

        // Then
        assertThat(property).isExactlyInstanceOf(CollectionProperty.class);
        assertThat(property.getName()).isEqualTo(Todo6CodecFixtures.TEST_STRINGS);
        assertThat(Todo6CodecFixtures.items(property).stream()
                .map(PropertyGraphCollectionCodecTest::itemRow)
                .collect(Collectors.toList())).containsExactly(
                        "-623340332|org.apache.jmeter.testelement.property.StringProperty|login failed",
                        "-623340332|org.apache.jmeter.testelement.property.StringProperty|login failed",
                        "-522775081|org.apache.jmeter.testelement.property.StringProperty|access denied");
        assertThat(codec.read((MultiProperty) property)).isEqualTo(requested);
    }

    @Test
    void keepsEmptyPresentCollectionDistinctFromAbsentCollection() {
        // Given
        CollectionPropertyCodec codec = new CollectionPropertyCodec();
        RecursiveValue empty = RecursiveValue.collection(
                Todo6CodecFixtures.COLLECTION_CLASS, Collections.<RecursiveValue>emptyList());
        RecursiveValue absent = RecursiveValue.absent(
                GraphType.COLLECTION, Todo6CodecFixtures.COLLECTION_CLASS);

        // When
        JMeterProperty present = codec.materialize("qa.collection", empty).get();

        // Then
        assertThat(present).isExactlyInstanceOf(CollectionProperty.class);
        assertThat(Todo6CodecFixtures.items(present)).isEmpty();
        assertThat(codec.materialize("qa.collection", absent)).isEmpty();
    }

    @Test
    void recursivelyRebuildsObservedNestedCollectionAndPreservesNamesAndClasses() {
        // Given
        CollectionProperty nested = new CollectionProperty(
                "nested-name", Collections.<JMeterProperty>emptyList());
        nested.addProperty(new StringProperty("inner-child", "before"));
        CollectionProperty observed = new CollectionProperty(
                "outer-name", Collections.<JMeterProperty>emptyList());
        observed.addProperty(nested);
        RecursiveValue requested = RecursiveValue.collection(
                CollectionProperty.class.getName(),
                Collections.singletonList(RecursiveValue.collection(
                        CollectionProperty.class.getName(),
                        Collections.singletonList(string("after")))));
        CollectionPropertyCodec codec = new CollectionPropertyCodec();

        // When
        JMeterProperty rebuilt = codec.materialize(observed, requested).get();

        // Then
        assertThat(rebuilt).isExactlyInstanceOf(CollectionProperty.class);
        assertThat(rebuilt).isNotSameAs(observed);
        JMeterProperty rebuiltNested = Todo6CodecFixtures.items(rebuilt).get(0);
        assertThat(rebuiltNested).isExactlyInstanceOf(CollectionProperty.class);
        assertThat(rebuiltNested.getName()).isEqualTo("nested-name");
        JMeterProperty rebuiltScalar = Todo6CodecFixtures.items(rebuiltNested).get(0);
        assertThat(itemRow(rebuiltScalar)).isEqualTo(
                "inner-child|org.apache.jmeter.testelement.property.StringProperty|after");
        assertThat(Todo6CodecFixtures.items(observed).get(0)).isSameAs(nested);
        assertThat(Todo6CodecFixtures.items(nested).get(0).getStringValue()).isEqualTo("before");
    }

    @Test
    void rejectsScalarShapeBeforeChangingObservedCollection() {
        // Given
        CollectionProperty observed = new CollectionProperty(
                "qa.collection", Collections.<JMeterProperty>emptyList());
        observed.addProperty(new StringProperty("original-name", "original-value"));
        List<String> before = Todo6CodecFixtures.items(observed).stream()
                .map(PropertyGraphCollectionCodecTest::itemRow)
                .collect(Collectors.toList());
        CollectionPropertyCodec codec = new CollectionPropertyCodec();

        // When / Then
        assertThatThrownBy(() -> codec.materialize(observed, string("wrong shape")))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.type: collection codec requires a collection value");
        assertThat(Todo6CodecFixtures.items(observed).stream()
                .map(PropertyGraphCollectionCodecTest::itemRow)
                .collect(Collectors.toList())).containsExactlyElementsOf(before);
    }

    @Test
    void rejectsPayloadOnAbsentBeforeCallerCanAttachProperty() {
        // Given
        ResponseAssertion assertion = Todo6CodecFixtures.responseAssertion("original");
        JMeterProperty before = assertion.getPropertyOrNull(Todo6CodecFixtures.TEST_STRINGS);
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("presence", "absent");
        value.put("property_class", Todo6CodecFixtures.COLLECTION_CLASS);
        value.put("items", Collections.emptyList());
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("property", Collections.<Object>singletonList("Asserion.test_strings"));
        document.put("type", "collection");
        document.put("value", value);

        // When / Then
        assertThatThrownBy(() -> new PropertyGraphDocumentMapper().fromDocument(document))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.value.items: payload is not allowed when presence is 'absent'");
        assertThat(assertion.getPropertyOrNull(Todo6CodecFixtures.TEST_STRINGS)).isSameAs(before);
    }

    private static RecursiveValue string(String value) {
        return RecursiveValue.scalar(GraphType.STRING, Todo6CodecFixtures.STRING_CLASS, value);
    }

    private static String itemRow(JMeterProperty property) {
        return property.getName() + "|" + property.getClass().getName()
                + "|" + property.getStringValue();
    }
}
