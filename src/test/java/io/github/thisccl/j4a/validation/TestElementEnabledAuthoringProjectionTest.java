package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.ApplyPatchParseException;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.read.ReadOptions;
import io.github.thisccl.j4a.read.YamlReadRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TestElementEnabledAuthoringProjectionTest {
    private static final List<Object> ENABLED =
            Collections.<Object>singletonList(TestElement.ENABLED);

    @Test
    void componentsAndReadReuseTheSameCanonicalBooleanState() {
        ConfigTestElement element = rawStringEnabledElement(false);

        List<ComponentCatalog.ComponentProperty> catalog = LocalComponentProperties.properties(
                element, ConfigTestElement.class, LocalPropertyGraphRuntimeContext.inProcess());
        List<ComponentCatalog.ComponentProperty> enabledMetadata = new ArrayList<>();
        for (ComponentCatalog.ComponentProperty property : catalog) {
            if (ENABLED.equals(property.address())) {
                enabledMetadata.add(property);
            }
        }
        assertThat(enabledMetadata).hasSize(1);
        assertThat(enabledMetadata.get(0).type()).isEqualTo("boolean");
        assertThat(enabledMetadata.get(0).writable()).isTrue();

        Map<String, Object> rendered = mapping(new Yaml().load(new YamlReadRenderer().render(
                plan(element),
                new ReadOptions(false, true, Integer.valueOf(0), null, ReadOptions.PropertyMode.ALL),
                LocalPropertyGraphRuntimeContext.inProcess())));
        Map<String, Object> root = mapping(rendered.get("root"));
        Map<String, Object> enabled = property(root, ENABLED);
        assertThat(enabled).containsEntry("type", "boolean")
                .containsEntry("value", Boolean.FALSE);
        assertThat(root.get("enabled")).isEqualTo(enabled.get("value"));
    }

    @Test
    void quotedEnabledValueIsRejectedAsStringBeforeMutation() {
        String yaml = "changes:\n"
                + "  - set:\n"
                + "      ref: jmx_fixture_1\n"
                + "      properties:\n"
                + "        - property: [TestElement.enabled]\n"
                + "          type: boolean\n"
                + "          value: \"false\"\n";

        assertThatThrownBy(() -> new ApplyPatchParser().parse(yaml))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("[TestElement.enabled]")
                .hasMessageContaining("expected type 'boolean'")
                .hasMessageContaining("actual type 'String'");
    }

    private static ConfigTestElement rawStringEnabledElement(boolean enabled) {
        ConfigTestElement element = new ConfigTestElement();
        element.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.config.gui.SimpleConfigGui");
        element.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
        element.setName("Enabled projection fixture");
        element.setProperty(new StringProperty(TestElement.ENABLED, Boolean.toString(enabled)));
        return element;
    }

    private static JmxTestPlan plan(TestElement element) {
        ListedHashTree tree = new ListedHashTree();
        tree.add(element);
        return new JmxTestPlan(tree);
    }

    private static Map<String, Object> property(
            Map<String, Object> component, List<Object> address) {
        for (Object value : list(component.get("properties"))) {
            Map<String, Object> candidate = mapping(value);
            if (address.equals(candidate.get("property"))) {
                return candidate;
            }
        }
        throw new AssertionError("missing read property: " + address);
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
