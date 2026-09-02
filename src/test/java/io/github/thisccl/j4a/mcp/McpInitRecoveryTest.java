package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpInitRecoveryTest {
    @Test
    void recoveryIsScopedToTheInitTool() {
        assertThat(McpInvocationContext
                .fromValidatedArguments("init", new LinkedHashMap<String, Object>()).initTool()).isTrue();
        assertThat(McpInvocationContext
                .fromValidatedArguments("set", new LinkedHashMap<String, Object>()).initTool()).isFalse();
    }

    @Test
    void existingTargetPublishesTwoUnexecutedDirectlyCallableChoices() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("out", "owned/existing.jmx");
        arguments.put("jmeter_home", "/opt/jmeter-fixture/apache-jmeter-5.6.3");
        arguments.put("name", "Plan A");
        arguments.put("threadGroupName", "Threads A");
        Map<String, Object> recovery = McpInvocationContext
                .fromValidatedArguments("init", arguments).initRecovery();
        java.util.List<Object> choices = list(recovery.get("choices"));
        assertThat(choices).hasSize(2);
        assertChoice(mapping(choices.get(0)), "overwrite", arguments, "owned/existing.jmx", true);
        assertChoice(mapping(choices.get(1)), "choose-output", arguments, "<different-output.jmx>", false);
    }

    private static void assertChoice(Map<String, Object> choice, String action,
            Map<String, Object> original, String out, boolean force) {
        assertThat(choice.keySet()).containsExactly("action", "tool", "arguments");
        assertThat(choice).containsEntry("action", action).containsEntry("tool", "init");
        Map<String, Object> arguments = mapping(choice.get("arguments"));
        assertThat(arguments).containsEntry("out", out)
                .containsEntry("jmeter_home", original.get("jmeter_home"))
                .containsEntry("name", original.get("name"))
                .containsEntry("threadGroupName", original.get("threadGroupName"));
        assertThat(arguments.containsKey("forceOut")).isEqualTo(force);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> list(Object value) { return (java.util.List<Object>) value; }
}
