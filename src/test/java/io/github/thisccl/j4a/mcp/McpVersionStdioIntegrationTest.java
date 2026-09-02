package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.VersionInfo;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpVersionStdioIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void packagedInitializeReportsTheEmbeddedPackageVersion() throws Exception {
        Path stderr = tempDir.resolve("mcp-version.stderr");
        try (McpPackagedStdioSession session = McpPackagedStdioSession.start(stderr)) {
            Map<String, Object> initialize = mapping(session.initialize().get("result"));

            assertThat(mapping(initialize.get("serverInfo")))
                    .containsEntry("name", "j4a")
                    .containsEntry("version", VersionInfo.version());
            session.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
