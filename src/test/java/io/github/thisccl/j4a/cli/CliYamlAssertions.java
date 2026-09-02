package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class CliYamlAssertions {
    private CliYamlAssertions() {
    }

    static Map<String, Object> parseMapping(String yaml) {
        Object parsed = new Yaml().load(yaml);
        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) parsed;
        return mapping;
    }
}
