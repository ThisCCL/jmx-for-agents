package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class LocalComponentAuthoringParityTest {
    @Test
    void finiteChoicePublishesExactRuntimeValuesAndLabels() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();

        LocalJMeterWorkerResponse detail = client.execute(LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), "org.apache.jmeter.control.gui.ThroughputControllerGui")).response();

        assertThat(detail.success()).as(detail.toJsonLine()).isTrue();
        Map<String, Object> document = mapping(new Yaml().load(detail.payload()));
        Map<String, Object> style = properties(document).stream()
                .filter(property -> Arrays.<Object>asList("ThroughputController.style")
                        .equals(property.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ThroughputController.style is missing"));
        assertThat(style.get("value_options")).isEqualTo(new Yaml().load(
                "- value: 0\n"
                + "  label: Total Executions\n"
                + "- value: 1\n"
                + "  label: Percent Executions\n"));
    }

    @Test
    void componentDetailScalarCanBeCopiedIntoAnAddOverlay() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        Path home = fixtures.localHome();
        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();

        LocalJMeterWorkerResponse detail = client.execute(LocalJMeterWorkerRequest.componentDetails(
                home, DefaultLocalProfilePluginFixtures.CLEAR_GUI_SENSITIVE_GUI_CLASS)).response();
        assertThat(detail.success()).as(detail.toJsonLine()).isTrue();
        assertThat(detail.payload()).contains("qa.constructor.only");

        Path output = fixtures.root().resolve("out/constructor-only-overlay.jmx");
        String patch = "changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: "
                + DefaultLocalProfilePluginFixtures.CLEAR_GUI_SENSITIVE_GUI_CLASS + "\n"
                + "      properties:\n"
                + "        - property: [qa.constructor.only]\n"
                + "          type: string\n"
                + "          value: requested\n";

        LocalJMeterWorkerResponse applied = client.execute(LocalJMeterWorkerRequest.applyPatchYaml(
                fixtures.root().resolve("basic.jmx"), home, patch, output, true)).response();
        assertThat(applied.success()).as(applied.toJsonLine()).isTrue();

        LocalJMeterWorkerResponse read = client.execute(LocalJMeterWorkerRequest.renderReadData(
                output, home, "20", null, "ALL", "true")).response();
        assertThat(read.success()).as(read.toJsonLine()).isTrue();
        assertThat(read.payload()).contains("qa.constructor.only", "requested");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> properties(Map<String, Object> document) {
        return (List<Map<String, Object>>) (List<?>) document.get("properties");
    }
}
