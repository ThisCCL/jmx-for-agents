package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MainComponentsCategoryPartialTest {
    @Test
    void failedMaterializationIsBoundedAndDoesNotAbortSuccessfulSiblings() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());

        CliTestResult result = runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "50");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        Map<String, Object> page = mapping(new Yaml().load(result.stdout()));
        assertThat(page).containsEntry("partial", Boolean.TRUE);
        List<Object> components = list(page.get("components"));
        Map<String, Object> failed = components.stream().map(MainComponentsCategoryPartialTest::mapping)
                .filter(item -> String.valueOf(item.get("component")).contains("MaterializationFailureSamplerGui"))
                .findFirst().orElseThrow(() -> new AssertionError("exploding runtime component missing"));
        Map<String, Object> error = mapping(failed.get("error"));
        assertThat(error).containsEntry("code", "COMPONENT_DETAIL_FAILED")
                .containsEntry("phase", "materialization");
        assertThat(String.valueOf(error.get("message"))).hasSizeLessThanOrEqualTo(512)
                .doesNotContain("\n\tat ", "Caused by:");
        assertThat(components.stream().map(MainComponentsCategoryPartialTest::mapping)
                .filter(item -> !item.containsKey("error"))).hasSizeGreaterThan(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }
}
