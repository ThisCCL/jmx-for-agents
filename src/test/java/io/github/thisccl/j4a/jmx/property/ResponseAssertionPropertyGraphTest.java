package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import io.github.thisccl.j4a.path.PropertyPath;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResponseAssertionPropertyGraphTest {
    @TempDir
    Path tempDir;

    @Test
    void runtimeResponseAssertionCollectionRemainsWritableWithoutComponentSpecificRule() {
        // Given
        ResponseAssertion assertion = Todo6CodecFixtures.responseAssertion(
                "login failed", "access denied");
        PropertyPath path = io.github.thisccl.j4a.path.TestPropertyPaths.properties("Asserion.test_strings");

        // When
        GraphSnapshot snapshot = new DefaultJMeterPropertyGraph()
                .inspect(assertion, Todo6CodecFixtures.runtimeContext());
        GraphNode node = snapshot.resolve(path);

        // Then
        assertThat(node.type()).isEqualTo(GraphType.COLLECTION);
        assertThat(node.value().propertyClass()).isEqualTo(Todo6CodecFixtures.COLLECTION_CLASS);
        assertThat(node.value().items()).extracting(RecursiveValue::scalarValue)
                .containsExactly("login failed", "access denied");
        assertThat(node.capability().ownership()).isEqualTo(GraphOwnership.USER);
        assertThat(node.capability().writableState()).isEqualTo(WritableState.WRITABLE);
        assertThat(node.capability().reason()).isEmpty();
        assertThat(snapshot.resolveWritable(path)).isSameAs(node);

        GraphNode identity = snapshot.resolve(io.github.thisccl.j4a.path.TestPropertyPaths.properties("TestElement.test_class"));
        assertThat(identity.capability().ownership()).isEqualTo(GraphOwnership.SYSTEM);
        assertThat(identity.capability().writableState()).isEqualTo(WritableState.READ_ONLY);
        assertThat(identity.capability().reason()).contains(
                "JMeter identity metadata is managed by the selected runtime");
    }

    @Test
    void pinSelectedSaveServiceReloadPreservesExactResponseAssertionMetadata() {
        // Given
        SaveServiceJmxLoader loader = Todo6CodecFixtures.loader();
        JmxTestPlan loaded = loader.load(Todo6CodecFixtures.fixture());
        Path saved = tempDir.resolve("pin-response-assertion.jmx");

        // When
        loader.save(loaded, saved);
        ResponseAssertion reloaded = Todo6CodecFixtures.responseAssertion(loader.load(saved));
        JMeterProperty property = reloaded.getPropertyOrNull(Todo6CodecFixtures.TEST_STRINGS);
        List<String> projection = Todo6CodecFixtures.items(property).stream()
                .map(ResponseAssertionPropertyGraphTest::itemRow)
                .collect(Collectors.toList());

        // Then
        assertThat(saved).isRegularFile().isNotEmptyFile();
        assertThat(property.getName()).isEqualTo(Todo6CodecFixtures.TEST_STRINGS);
        assertThat(property.getClass().getName()).isEqualTo(Todo6CodecFixtures.COLLECTION_CLASS);
        assertThat(projection).containsExactly(
                "-623340332|org.apache.jmeter.testelement.property.StringProperty|login failed",
                "-522775081|org.apache.jmeter.testelement.property.StringProperty|access denied");
    }

    @Test
    void materializesAttachesAndPersistsOrderedDuplicateStringsThroughSelectedSaveService() {
        // Given
        SaveServiceJmxLoader loader = Todo6CodecFixtures.loader();
        JmxTestPlan loaded = loader.load(Todo6CodecFixtures.fixture());
        ResponseAssertion assertion = Todo6CodecFixtures.responseAssertion(loaded);
        RecursiveValue requested = RecursiveValue.collection(
                Todo6CodecFixtures.COLLECTION_CLASS,
                Arrays.asList(
                        RecursiveValue.scalar(
                                GraphType.STRING, Todo6CodecFixtures.STRING_CLASS, "login failed"),
                        RecursiveValue.scalar(
                                GraphType.STRING, Todo6CodecFixtures.STRING_CLASS, "login failed"),
                        RecursiveValue.scalar(
                                GraphType.STRING, Todo6CodecFixtures.STRING_CLASS, "access denied")));
        CollectionPropertyCodec codec = new CollectionPropertyCodec();
        Path saved = tempDir.resolve("green-response-assertion.jmx");

        // When
        JMeterProperty materialized = codec
                .materialize(Todo6CodecFixtures.TEST_STRINGS, requested)
                .get();
        assertion.setProperty(materialized);
        loader.save(loaded, saved);
        ResponseAssertion reloaded = Todo6CodecFixtures.responseAssertion(loader.load(saved));
        JMeterProperty persisted = reloaded.getPropertyOrNull(Todo6CodecFixtures.TEST_STRINGS);

        // Then
        assertThat(persisted.getClass().getName()).isEqualTo(Todo6CodecFixtures.COLLECTION_CLASS);
        assertThat(persisted.getName()).isEqualTo(Todo6CodecFixtures.TEST_STRINGS);
        assertThat(Todo6CodecFixtures.items(persisted).stream()
                .map(ResponseAssertionPropertyGraphTest::itemRow)
                .collect(Collectors.toList())).containsExactly(
                        "-623340332|org.apache.jmeter.testelement.property.StringProperty|login failed",
                        "-623340332|org.apache.jmeter.testelement.property.StringProperty|login failed",
                        "-522775081|org.apache.jmeter.testelement.property.StringProperty|access denied");
        assertThat(codec.read((MultiProperty) persisted)).isEqualTo(requested);
    }

    private static String itemRow(JMeterProperty property) {
        return property.getName() + "|" + property.getClass().getName()
                + "|" + property.getStringValue();
    }
}
