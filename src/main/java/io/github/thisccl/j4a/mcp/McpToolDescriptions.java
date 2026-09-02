package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.AgentGuidance;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class McpToolDescriptions {
    private static final String RESOURCE_ROOT =
            "io/github/thisccl/j4a/mcp/tool-descriptions/";

    private McpToolDescriptions() {
    }

    static String description(String toolName) {
        String resource = RESOURCE_ROOT + toolName + ".txt";
        InputStream input = McpToolDescriptions.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("Missing MCP tool description resource: " + resource);
        }
        try {
            try {
                String description = readUtf8(input, resource).trim();
                String guidance = AgentGuidance.mcpToolGuidance(toolName);
                if (!guidance.isEmpty()) {
                    description += "\n\n" + guidance;
                }
                return description;
            } finally {
                input.close();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load MCP tool description resource: " + resource, exception);
        }
    }

    private static String readUtf8(InputStream input, String resource) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) {
            throw new IllegalStateException("Empty MCP tool description resource: " + resource);
        }
        return text;
    }
}
