package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.components.ComponentCatalogRenderer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.gui.AssertionGui;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PropertyGraphComponentProjectionTest {
    private static final String ASSERTION_GUI =
            "org.apache.jmeter.assertions.gui.AssertionGui";
    private static final String TEST_STRINGS = "Asserion.test_strings";
    private static final String COLLECTION_PROPERTY =
            "org.apache.jmeter.testelement.property.CollectionProperty";

    @Test
    void responseAssertionOrdinaryAndDiagnosticDetailsExposeTheirExactProjections() throws Exception {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(180), Duration.ofSeconds(180));
        LocalJMeterWorkerResult result = client.execute(
                LocalJMeterWorkerRequest.componentDetails(jmeterHome(), ASSERTION_GUI));
        LocalJMeterWorkerResult diagnosticResult = client.execute(
                LocalJMeterWorkerRequest.componentDetails(jmeterHome(), ASSERTION_GUI, true));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(diagnosticResult.response().success())
                .as(diagnosticResult.response().toJsonLine()).isTrue();
        Map<String, Object> document = mapping(new Yaml().load(result.response().payload()));
        Map<String, Object> property = property(document, TEST_STRINGS);

        assertThat(property).containsEntry(
                        "property", Collections.<Object>singletonList(TEST_STRINGS))
                .containsEntry("type", "collection")
                .containsEntry("value_shape", "collection.items")
                .containsOnlyKeys("property", "type", "value_shape");

        Map<String, Object> diagnosticDocument = mapping(
                new Yaml().load(diagnosticResult.response().payload()));
        assertThat(document).doesNotContainKey("runtime_metadata_status");
        assertThat(diagnosticDocument).containsEntry("runtime_metadata_status", "runtime-proven");
        Map<String, Object> diagnosticProperty = property(diagnosticDocument, TEST_STRINGS);
        assertThat(diagnosticProperty)
                .containsEntry("key", false)
                .containsEntry("writable", true)
                .containsEntry("ownership", "user")
                .containsEntry("representation_source", "runtime")
                .containsEntry("required_property_class", COLLECTION_PROPERTY)
                .doesNotContainKey("value_template");
        assertThat(result.response().payload()).doesNotContain(
                "type: unsupported", "raw_group:", "registration_kind:", "resource_key:",
                "allowed_parent:", "placement:", "addable:", "gui_openable:");
    }

    @Test
    void metadataProjectionReportsAvailabilityWithoutChangingKnownPropertyRows() {
        ResponseAssertion defaults = new ResponseAssertion();
        LocalComponentProperties.MetadataProjection emptyMetadata = LocalComponentProperties.propertiesWithMetadata(
                defaults, AssertionGui.class, LocalPropertyGraphRuntimeContext.inProcess(),
                LocalComponentProperties.syntheticStableMetadataSource(false));
        LocalComponentProperties.MetadataProjection unavailableMetadata =
                LocalComponentProperties.propertiesWithMetadata(
                        defaults, AssertionGui.class, LocalPropertyGraphRuntimeContext.inProcess(),
                        LocalComponentProperties.syntheticStableMetadataSource(true));

        assertThat(emptyMetadata.runtimeMetadataStatus()).isEqualTo("runtime-proven");
        assertThat(unavailableMetadata.runtimeMetadataStatus())
                .isEqualTo("runtime-metadata-unavailable");
        Map<String, Object> emptyDocument = diagnosticDocument(emptyMetadata);
        Map<String, Object> unavailableDocument = diagnosticDocument(unavailableMetadata);
        assertThat(emptyDocument).containsEntry("runtime_metadata_status", "runtime-proven");
        assertThat(unavailableDocument).containsEntry(
                "runtime_metadata_status", "runtime-metadata-unavailable");
        assertThat(emptyDocument.get("properties")).isEqualTo(unavailableDocument.get("properties"));
    }

    @Test
    void runtimeScalarProjectionKeepsFloatAndDoublePublicTypesDistinct() {
        ResponseAssertion defaults = new ResponseAssertion();
        defaults.setProperty(new FloatProperty("qa.float", 1.25F));
        defaults.setProperty(new DoubleProperty("qa.double", 1.5D));

        List<ComponentCatalog.ComponentProperty> properties = LocalComponentProperties.properties(
                defaults, AssertionGui.class);

        assertThat(projectedProperty(properties, "qa.float").type()).isEqualTo("float");
        assertThat(projectedProperty(properties, "qa.double").type()).isEqualTo("double");
    }

    @Test
    void populatedRecursiveCollectionDoesNotManufactureAValueTemplate() {
        ResponseAssertion populated = new ResponseAssertion();
        populated.addTestString("observed-string");
        ComponentCatalog.ComponentProperty property = projectedProperty(
                LocalComponentProperties.properties(populated, AssertionGui.class),
                TEST_STRINGS);

        assertThat(property.valueTemplate()).isNull();
        assertThat(property.type()).isEqualTo("collection");
        assertThat(property.valueShape()).isEqualTo("collection.items");
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    private static Map<String, Object> property(Map<String, Object> document, String path) {
        for (Object value : list(document.get("properties"))) {
            Map<String, Object> candidate = mapping(value);
            if (Collections.<Object>singletonList(path).equals(candidate.get("property"))) {
                return candidate;
            }
        }
        throw new AssertionError("missing component property: " + path);
    }

    private static ComponentCatalog.ComponentProperty projectedProperty(
            List<ComponentCatalog.ComponentProperty> properties, String path) {
        for (ComponentCatalog.ComponentProperty property : properties) {
            if (Collections.<Object>singletonList(path).equals(property.address())) {
                return property;
            }
        }
        throw new AssertionError("missing projected component property: " + path);
    }

    private static Map<String, Object> diagnosticDocument(
            LocalComponentProperties.MetadataProjection metadata) {
        ComponentCatalog.ComponentDefinition definition = new ComponentCatalog.ComponentDefinition(
                "assertion", "Assertions", ASSERTION_GUI, "Response Assertion", metadata.properties(),
                AssertionGui.class.getName(), "GUI_COMPONENT", metadata.runtimeMetadataStatus());
        return mapping(new Yaml().load(
                new ComponentCatalogRenderer().renderRuntimeComponent(definition, true)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
