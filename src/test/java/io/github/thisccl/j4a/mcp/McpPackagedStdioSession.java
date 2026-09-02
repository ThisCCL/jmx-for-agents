package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.VersionInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.Yaml;

final class McpPackagedStdioSession implements AutoCloseable {
    private final Process process;
    private final PrintWriter requests;
    private final BufferedReader responses;
    private final Path stderr;
    private int requestCount;

    private McpPackagedStdioSession(Process process, Path stderr) {
        this.process = process;
        this.stderr = stderr;
        this.requests = new PrintWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
        this.responses = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    static McpPackagedStdioSession start(Path stderr) throws IOException {
        Path jar = Paths.get(System.getProperty("user.dir"), "build", "libs",
                "j4a-" + VersionInfo.version() + "-all.jar");
        assertThat(jar).isRegularFile();
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                java, "-cp", jar.toString(), J4aMcpServer.class.getName())
                .redirectError(stderr.toFile())
                .start();
        return new McpPackagedStdioSession(process, stderr);
    }

    Map<String, Object> initialize() throws IOException {
        Map<String, Object> client = new LinkedHashMap<String, Object>();
        client.put("name", "mcp-array-address-rows-test");
        client.put("version", "1");
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("protocolVersion", "2025-06-18");
        params.put("capabilities", new LinkedHashMap<String, Object>());
        params.put("clientInfo", client);
        Map<String, Object> result = request(1, "initialize", params);
        requests.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        return result;
    }

    Map<String, Object> toolsList(int id) throws IOException {
        return request(id, "tools/list", new LinkedHashMap<String, Object>());
    }

    Map<String, Object> call(int id, String tool, Map<String, Object> arguments) throws IOException {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", tool);
        params.put("arguments", arguments);
        return mapping(request(id, "tools/call", params).get("result"));
    }

    int requestCount() {
        return requestCount;
    }

    void shutdown() throws IOException {
        request(999, "shutdown", new LinkedHashMap<String, Object>());
    }

    private Map<String, Object> request(int id, String method, Map<String, Object> params) throws IOException {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Integer.valueOf(id));
        request.put("method", method);
        request.put("params", params);
        requestCount++;
        requests.println(McpJson.write(request));
        String line = responses.readLine();
        if (line == null) {
            throw new IOException("MCP stdio ended before responding; stderr=" + stderrText());
        }
        return mapping(new Yaml().load(line));
    }

    @Override
    public void close() throws Exception {
        requests.close();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("MCP stdio subprocess did not exit");
        }
        if (process.exitValue() != 0) {
            throw new IOException("MCP stdio subprocess failed: " + stderrText());
        }
    }

    private String stderrText() throws IOException {
        return Files.exists(stderr)
                ? new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8)
                : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
