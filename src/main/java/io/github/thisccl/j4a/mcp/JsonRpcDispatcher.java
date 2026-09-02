package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.cli.J4aCommandExecutor;
import java.util.Map;

final class JsonRpcDispatcher {
    private final JsonRpcToolCallHandler toolCallHandler;

    JsonRpcDispatcher() {
        this(new J4aCommandExecutor(), new McpCommandResultAdapter());
    }

    JsonRpcDispatcher(J4aCommandExecutor commandExecutor, McpCommandResultAdapter commandResultAdapter) {
        this(new JsonRpcToolCallHandler(commandExecutor, commandResultAdapter));
    }

    JsonRpcDispatcher(
            J4aCommandExecutor commandExecutor,
            McpCommandResultAdapter commandResultAdapter,
            Map<String, String> environment) {
        this(new JsonRpcToolCallHandler(commandExecutor, commandResultAdapter, environment));
    }

    JsonRpcDispatcher(JsonRpcToolCallHandler toolCallHandler) {
        this.toolCallHandler = toolCallHandler;
    }

    boolean dispatch(String line, McpStdioServer server) {
        Object parsed;
        try {
            parsed = McpJson.parseRequest(line);
        } catch (IllegalArgumentException exception) {
            server.write(JsonRpcResponse.error(null, -32700, "Parse error: " + exception.getMessage()));
            return false;
        }
        if (!(parsed instanceof Map)) {
            server.write(JsonRpcResponse.error(null, -32600, "Invalid Request: expected JSON object."));
            return false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) parsed;
        Object id = request.get("id");
        Object methodValue = request.get("method");
        if (!(methodValue instanceof String)) {
            server.write(JsonRpcResponse.error(id, -32600, "Invalid Request: method is required."));
            return false;
        }

        String method = (String) methodValue;
        if ("notifications/initialized".equals(method)) {
            return false;
        }
        if (!request.containsKey("id")) {
            return false;
        }
        if ("initialize".equals(method)) {
            server.write(JsonRpcResponse.success(id, McpProtocol.initializeResult(request.get("params"))));
            server.initializeResponded();
            return false;
        }
        if ("tools/list".equals(method)) {
            server.write(JsonRpcResponse.success(id, McpProtocol.toolsListResult()));
            return false;
        }
        if ("tools/call".equals(method)) {
            toolCallHandler.dispatch(id, request.get("params"), server);
            return false;
        }
        if ("shutdown".equals(method)) {
            server.write(JsonRpcResponse.success(id, null));
            return true;
        }
        server.write(JsonRpcResponse.error(id, -32601, "Method not found: " + method));
        return false;
    }
}
