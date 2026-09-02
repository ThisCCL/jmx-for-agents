package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class J4aCommandExecutorStructuredDataTest {
    @Test
    void categoriesStructuredDataEqualsTheEmittedYamlModel() {
        String yaml = "categories:\n"
                + "- category: sampler\n"
                + "  label: Samplers\n"
                + "  component_count: 2\n"
                + "- category: timer\n"
                + "  label: Timers\n"
                + "  component_count: 0\n";

        assertThat(J4aCommandExecutor.structuredData(new String[] {"categories", "ls"}, yaml))
                .isEqualTo(parsedMapping(yaml))
                .doesNotContainKeys("command", "format");
    }

    @Test
    void componentsStructuredDataPreservesAKnownEmptyCategory() {
        String yaml = "categories:\n"
                + "- category: timer\n"
                + "  label: Timers\n"
                + "  components: []\n";

        assertThat(J4aCommandExecutor.structuredData(
                new String[] {"components", "--category", "timer"}, yaml))
                .isEqualTo(parsedMapping(yaml))
                .doesNotContainKeys("command", "format");
    }

    @Test
    void componentsStructuredDataRejectsNonMappingYamlAtTheParsingBoundary() {
        assertThatThrownBy(() -> J4aCommandExecutor.structuredData(
                new String[] {"components"}, "- not\n- a mapping\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("components output must be a YAML mapping");
    }

    @Test
    void categoriesStructuredDataRejectsMalformedYamlInsteadOfReturningMisleadingEmptyData() {
        assertThatThrownBy(() -> J4aCommandExecutor.structuredData(
                new String[] {"categories", "ls"}, "categories: [unterminated\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categories output must be valid YAML");
    }

    @Test
    void applyStructuredDataCarriesOnlyCompactIdentityReceiptFacts() {
        String output = "appliedCount: 1\n"
                + "createdRefs:\n- {alias: controller, ref: abcdefghijklmnop}\n"
                + "deletedRefs: [deleted123456789]\n"
                + "changeResults:\n"
                + "- {index: 0, operation: add, status: committed, context: {alias: controller, component: example.Controller, parent: parent, position: last, properties: []}, resultRef: abcdefghijklmnop}\n";

        Map<String, Object> data = J4aCommandExecutor.structuredData(
                new String[] {"apply", "plan.jmx", "--override"}, output);

        assertThat(data)
                .containsEntry("writeMode", "in-place")
                .containsEntry("appliedCount", Integer.valueOf(1))
                .containsEntry("deletedRefs", java.util.Collections.singletonList("deleted123456789"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> created = (List<Map<String, String>>) data.get("createdRefs");
        assertThat(created).hasSize(1);
        assertThat(created.get(0))
                .containsEntry("alias", "controller")
                .containsEntry("ref", "abcdefghijklmnop")
                .hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsedMapping(String yaml) {
        return (Map<String, Object>) new Yaml().load(yaml);
    }
}
