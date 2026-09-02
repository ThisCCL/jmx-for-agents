package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class J4aMcpLocalOnlySurfaceTest {
    @Test
    void schemasExposeOnlyOptionalJmeterHomeRuntimeSelection() {
        List<Object> tools = McpTools.list();

        assertThat(tools).allSatisfy(rawTool -> {
            Map<?, ?> tool = (Map<?, ?>) rawTool;
            Map<?, ?> schema = (Map<?, ?>) tool.get("inputSchema");
            Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
            assertThat(properties.containsKey("profile")).isFalse();
            assertThat(properties.containsKey("validationMode")).isFalse();
            assertThat(properties.containsKey("validation_mode")).isFalse();
            assertThat(properties.containsKey("jmeterHome")).isFalse();
            assertThat(properties.containsKey("jmeter_home")).isTrue();
            assertThat(String.valueOf(tool)).doesNotContain("pure", "bundled", "validation mode");
            assertThat(String.valueOf(tool)).doesNotContain("jmeterHome");
            if ("components".equals(tool.get("name"))) {
                assertThat(String.valueOf(tool)).contains("runtime_metadata_status");
                assertThat(String.valueOf(tool)).doesNotContain(
                        "profile", "validationMode", "validation_mode", "raw_groups", "registration", "admission",
                        "support_level");
            }
        });
    }
}
