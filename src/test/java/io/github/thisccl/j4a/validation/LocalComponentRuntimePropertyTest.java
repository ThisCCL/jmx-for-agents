package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Window;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class LocalComponentRuntimePropertyTest {
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

    @Test
    void reportsRuntimeRepresentationWithoutCustomizableJudgment() throws Exception {
        fixtures.ensure();
        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.componentDetails(fixtures.localHome(),
                        "org.apache.jmeter.modifiers.JSR223PreProcessor", true));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.response().payload()).contains(
                "writable: true", "ownership: user", "representation_source:");
        assertThat(result.response().payload()).doesNotContain(
                "kind:", "customizable:", "type: unsupported");
    }

    @Test
    void emptyHttpRequestDefaultsExposeGuiBoundDomainAndPort() throws Exception {
        int windowsBefore = Window.getWindows().length;
        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.componentDetails(
                        io.github.thisccl.j4a.TestJMeterRuntime.home(),
                        "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui", true));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> document = new Yaml().load(result.response().payload());
        assertThat(document).containsEntry("runtime_metadata_status", "runtime-proven");
        assertThat(property(document, "HTTPSampler.domain"))
                .containsEntry("type", "string")
                .containsEntry("key", false)
                .containsEntry("writable", true)
                .containsEntry("representation_source", "gui_semantic_descriptor")
                .doesNotContainKeys("value", "default");
        assertThat(property(document, "HTTPSampler.port"))
                .containsEntry("type", "int")
                .containsEntry("key", false)
                .containsEntry("writable", true)
                .containsEntry("representation_source", "gui_semantic_descriptor")
                .doesNotContainKeys("value", "default");
        assertThat(result.response().payload()).doesNotContain(
                "complete_gui", "gui_completeness", "HttpDefaultsGui#", "bindingGroup");
        assertThat(Window.getWindows()).hasSize(windowsBefore);
    }

    @Test
    void emptyHttpArgumentsExposeExactGuiConsumerInsteadOfPublicBaseType() throws Exception {
        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.componentDetails(
                        io.github.thisccl.j4a.TestJMeterRuntime.home(),
                        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui", true));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> document = new Yaml().load(result.response().payload());
        assertThat(property(document, "HTTPsampler.Arguments"))
                .containsEntry("type", "rows")
                .containsEntry("row_type", "org.apache.jmeter.protocol.http.util.HTTPArgument")
                .containsEntry("representation_source", "gui_semantic_consumer");
    }

    @Test
    void rollbackDisablesOnlyGuiSemanticEvidenceInTheWorker() throws Exception {
        String previous = System.getProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY);
        try {
            System.setProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY, "true");
            LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                    LocalJMeterWorkerRequest.componentDetails(
                            io.github.thisccl.j4a.TestJMeterRuntime.home(),
                            "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui", true));

            assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
            assertThat(result.response().payload())
                    .contains("runtime_metadata_status: runtime-proven")
                    .doesNotContain(
                            "gui_semantic_descriptor", "gui_semantic_consumer",
                            "HTTPSampler.domain", "HTTPSampler.port");
        } finally {
            if (previous == null) {
                System.clearProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY);
            } else {
                System.setProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY, previous);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> document, String name) {
        for (Map<String, Object> property : (List<Map<String, Object>>) document.get("properties")) {
            if (java.util.Collections.singletonList(name).equals(property.get("property"))) {
                return property;
            }
        }
        throw new AssertionError("Missing property " + name);
    }
}
