package io.github.thisccl.j4a.mcp;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;

final class McpStdioServer {
    static final int MAX_JSON_RPC_LINE_BYTES = 8 * 1024 * 1024;
    private final InputStream input;
    private final PrintStream stdout;
    private final JsonRpcDispatcher dispatcher;
    private final J4aMcpRuntimePool runtimePool;
    private final Map<String, String> environment;

    McpStdioServer(InputStream input, PrintStream stdout) {
        this(input, stdout, System.getenv());
    }

    McpStdioServer(InputStream input, PrintStream stdout, Map<String, String> environment) {
        this.input = new BufferedInputStream(input);
        this.stdout = stdout;
        this.runtimePool = new J4aMcpRuntimePool();
        this.environment = new LinkedHashMap<String, String>(environment);
        this.dispatcher = new JsonRpcDispatcher(
                new io.github.thisccl.j4a.cli.J4aCommandExecutor(runtimePool.workerClient()),
                new McpCommandResultAdapter(), this.environment);
    }

    int run() throws IOException {
        try {
            Line line;
            while ((line = readLine()) != null) {
                if (line.oversized) {
                    write(JsonRpcResponse.error(null, -32600,
                            "Invalid Request: JSON-RPC line exceeds the 8 MiB maximum."));
                    continue;
                }
                if (line.value.trim().isEmpty()) {
                    continue;
                }
                boolean shutdown = dispatcher.dispatch(line.value, this);
                if (shutdown) {
                    break;
                }
            }
            stdout.flush();
            return 0;
        } finally {
            runtimePool.close();
        }
    }

    private Line readLine() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean oversized = false;
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() < MAX_JSON_RPC_LINE_BYTES) {
                bytes.write(value);
            } else {
                oversized = true;
            }
        }
        if (value == -1 && bytes.size() == 0 && !oversized) {
            return null;
        }
        byte[] lineBytes = bytes.toByteArray();
        int length = lineBytes.length;
        if (length > 0 && lineBytes[length - 1] == '\r') {
            length--;
        }
        return new Line(new String(lineBytes, 0, length, StandardCharsets.UTF_8), oversized);
    }

    private static final class Line {
        private final String value;
        private final boolean oversized;

        private Line(String value, boolean oversized) {
            this.value = value;
            this.oversized = oversized;
        }
    }

    void write(Map<String, Object> message) {
        stdout.println(McpJson.write(message));
    }

    void initializeResponded() {
        stdout.flush();
        runtimePool.warmUpDefault(environment);
    }
}
