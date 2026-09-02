package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.Test;

class PropertyGraphScalarCodecTest {
    @Test
    void pinCurrentDiscoveryDtoForEveryScalarFamily() {
        // Given
        List<JMeterProperty> properties = Arrays.<JMeterProperty>asList(
                new StringProperty("qa.string", "text"),
                new BooleanProperty("qa.boolean", true),
                new IntegerProperty("qa.int", 42),
                new LongProperty("qa.long", 1234567890123L),
                new FloatProperty("qa.float", 1.25F),
                new DoubleProperty("qa.double", 1.25D),
                new NullProperty("qa.null"));

        // When
        List<String> rows = properties.stream()
                .map(PropertyGraphScalarCodecTest::discoveryRow)
                .collect(Collectors.toList());

        // Then
        assertThat(rows).containsExactly(
                "STRING|PRESENT|org.apache.jmeter.testelement.property.StringProperty|String|text",
                "BOOLEAN|PRESENT|org.apache.jmeter.testelement.property.BooleanProperty|Boolean|true",
                "INT|PRESENT|org.apache.jmeter.testelement.property.IntegerProperty|Integer|42",
                "LONG|PRESENT|org.apache.jmeter.testelement.property.LongProperty|Long|1234567890123",
                "FLOAT|PRESENT|org.apache.jmeter.testelement.property.FloatProperty|Float|1.25",
                "DOUBLE|PRESENT|org.apache.jmeter.testelement.property.DoubleProperty|Double|1.25",
                "NULL|PRESENT|org.apache.jmeter.testelement.property.NullProperty|null|<null>");
    }

    @Test
    void pinAbsentScalarDtoHasNoPayload() {
        // Given
        String propertyClass = StringProperty.class.getName();

        // When
        RecursiveValue value = RecursiveValue.absent(GraphType.STRING, propertyClass);

        // Then
        assertThat(value.type()).isEqualTo(GraphType.STRING);
        assertThat(value.presence()).isEqualTo(GraphPresence.ABSENT);
        assertThat(value.propertyClass()).isEqualTo(propertyClass);
        assertThatThrownBy(value::scalarValue)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scalar payload is not available");
    }

    @Test
    void materializesEveryCanonicalScalarWithExactNameClassAndValue() {
        // Given
        ScalarPropertyCodec codec = new ScalarPropertyCodec();
        List<RecursiveValue> values = Arrays.asList(
                RecursiveValue.scalar(GraphType.STRING, StringProperty.class.getName(), "text"),
                RecursiveValue.scalar(GraphType.BOOLEAN, BooleanProperty.class.getName(), true),
                RecursiveValue.scalar(GraphType.INT, IntegerProperty.class.getName(), 42),
                RecursiveValue.scalar(GraphType.LONG, LongProperty.class.getName(), 1234567890123L),
                RecursiveValue.scalar(GraphType.FLOAT, FloatProperty.class.getName(), 1.25F),
                RecursiveValue.scalar(GraphType.DOUBLE, DoubleProperty.class.getName(), 1.25D),
                RecursiveValue.presentNull(NullProperty.class.getName()));

        // When
        List<JMeterProperty> properties = new java.util.ArrayList<JMeterProperty>();
        for (int index = 0; index < values.size(); index++) {
            properties.add(codec.materialize("qa." + index, values.get(index)).get());
        }

        // Then
        assertThat(properties.stream().map(PropertyGraphScalarCodecTest::materializedRow)
                .collect(Collectors.toList())).containsExactly(
                        "qa.0|StringProperty|text",
                        "qa.1|BooleanProperty|true",
                        "qa.2|IntegerProperty|42",
                        "qa.3|LongProperty|1234567890123",
                        "qa.4|FloatProperty|1.25",
                        "qa.5|DoubleProperty|1.25",
                        "qa.6|NullProperty|");
        assertThat(properties.stream().map(codec::read).collect(Collectors.toList()))
                .containsExactlyElementsOf(values);
    }

    @Test
    void rejectsDoubleForFloatPropertyInsteadOfNarrowing() {
        // Given
        ScalarPropertyCodec codec = new ScalarPropertyCodec();
        RecursiveValue wrongType = RecursiveValue.scalar(
                GraphType.DOUBLE, FloatProperty.class.getName(), 1.234567890123D);

        // When / Then
        assertThatThrownBy(() -> codec.materialize("qa.float", wrongType))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.property_class: double requires DoubleProperty");
    }

    @Test
    void rejectsFloatInputForObservedDoublePropertyClass() {
        // Given
        ScalarPropertyCodec codec = new ScalarPropertyCodec();
        RecursiveValue wrongClass = RecursiveValue.scalar(
                GraphType.FLOAT, DoubleProperty.class.getName(), 1.25F);

        // When / Then
        assertThatThrownBy(() -> codec.materialize("qa.double", wrongClass))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.property_class: float requires FloatProperty");
    }

    @Test
    void rejectsNonFiniteFloatAndDoubleValuesAtTheScalarBoundary() {
        assertThatThrownBy(() -> RecursiveValue.scalar(
                GraphType.FLOAT, FloatProperty.class.getName(), Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> RecursiveValue.scalar(
                GraphType.FLOAT, FloatProperty.class.getName(), Float.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> RecursiveValue.scalar(
                GraphType.DOUBLE, DoubleProperty.class.getName(), Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void preservesObservedRawScalarClassWithoutMutatingTemplate() {
        // Given
        ScalarPropertyCodec codec = new ScalarPropertyCodec();
        Todo6RawStringProperty observed = new Todo6RawStringProperty("qa.raw", "before");
        RecursiveValue replacement = RecursiveValue.scalar(
                GraphType.STRING, Todo6RawStringProperty.class.getName(), "after");

        // When
        JMeterProperty materialized = codec.materialize(observed, replacement).get();

        // Then
        assertThat(materialized).isExactlyInstanceOf(Todo6RawStringProperty.class);
        assertThat(materialized).isNotSameAs(observed);
        assertThat(materialized.getName()).isEqualTo("qa.raw");
        assertThat(materialized.getStringValue()).isEqualTo("after");
        assertThat(observed.getStringValue()).isEqualTo("before");
    }

    @Test
    void absentScalarMaterializesNoProperty() {
        // Given
        ScalarPropertyCodec codec = new ScalarPropertyCodec();
        RecursiveValue absent = RecursiveValue.absent(
                GraphType.STRING, StringProperty.class.getName());

        // When / Then
        assertThat(codec.materialize("qa.absent", absent)).isEmpty();
    }

    @Test
    void rejectsCollectionShapeAtScalarBoundary() {
        // Given
        ScalarPropertyCodec codec = new ScalarPropertyCodec();
        RecursiveValue collection = RecursiveValue.collection(
                Todo6CodecFixtures.COLLECTION_CLASS, java.util.Collections.<RecursiveValue>emptyList());

        // When / Then
        assertThatThrownBy(() -> codec.materialize("qa.collection", collection))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage("$.type: scalar codec requires a scalar value");
    }

    private static String discoveryRow(JMeterProperty property) {
        RecursiveValue value = RuntimePropertyValueDiscovery.read(property);
        Object scalar = value.scalarValue();
        return value.type() + "|" + value.presence() + "|" + value.propertyClass() + "|"
                + (scalar == null ? "null" : scalar.getClass().getSimpleName()) + "|"
                + (scalar == null ? "<null>" : scalar);
    }

    private static String materializedRow(JMeterProperty property) {
        return property.getName() + "|" + property.getClass().getSimpleName()
                + "|" + property.getStringValue();
    }

}
