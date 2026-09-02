package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.cli.CommandResult;
import io.github.thisccl.j4a.cli.J4aCommandExecutor;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonRpcToolCallHandler {
    interface CommandExecution {
        CommandResult execute(String[] args, Map<String, String> environment, java.io.InputStream stdin);
    }

    private final CommandExecution commandExecution;
    private final McpCommandResultAdapter commandResultAdapter;
    private final Map<String, String> environment;

    JsonRpcToolCallHandler(J4aCommandExecutor commandExecutor, McpCommandResultAdapter commandResultAdapter) {
        this(commandExecutor, commandResultAdapter, System.getenv());
    }

    JsonRpcToolCallHandler(
            J4aCommandExecutor commandExecutor,
            McpCommandResultAdapter commandResultAdapter,
            Map<String, String> environment) {
        this(new CommandExecution() {
            @Override
            public CommandResult execute(String[] args, Map<String, String> selectedEnvironment, java.io.InputStream stdin) {
                return commandExecutor.execute(args, selectedEnvironment, stdin);
            }
        }, commandResultAdapter, environment);
    }

    JsonRpcToolCallHandler(
            CommandExecution commandExecution,
            McpCommandResultAdapter commandResultAdapter,
            Map<String, String> environment) {
        this.commandExecution = commandExecution;
        this.commandResultAdapter = commandResultAdapter;
        this.environment = new LinkedHashMap<String, String>(environment);
    }

    void dispatch(Object id, Object paramsValue, McpStdioServer server) {
        if (!(paramsValue instanceof Map)) {
            server.write(JsonRpcResponse.error(id, -32602, "Invalid params: tools/call params must be an object."));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) paramsValue;
        Object nameValue = params.get("name");
        if (!(nameValue instanceof String) || ((String) nameValue).trim().isEmpty()) {
            server.write(JsonRpcResponse.error(id, -32602, "Invalid params: tool name is required."));
            return;
        }

        String toolName = (String) nameValue;
        if (!McpTools.publicTool(toolName)) {
            server.write(JsonRpcResponse.error(id, -32601, "Tool not found: " + toolName));
            return;
        }
        Object argumentsValue = params.get("arguments");
        if (!(argumentsValue instanceof Map)) {
            server.write(JsonRpcResponse.error(id, -32602, "Invalid params: " + toolName + " arguments must be an object."));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsedArguments = (Map<String, Object>) argumentsValue;
        Map<String, Object> arguments;
        try {
            arguments = McpJsonNumbers.toolArguments(toolName, parsedArguments);
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage() + ". Correct the named arguments and call " + toolName + " again.";
            server.write(JsonRpcResponse.success(id, commandResultAdapter.invalidArguments(
                    message, argumentRecovery(toolName, message))));
            return;
        }
        String argumentError = McpToolArgumentValidator.validate(toolName, arguments);
        if (argumentError != null) {
            server.write(JsonRpcResponse.success(id, commandResultAdapter.invalidArguments(
                    argumentError, argumentRecovery(toolName, argumentError))));
            return;
        }
        McpToolInvocation invocation = McpToolInvocation.from(toolName, arguments);
        if (!invocation.valid()) {
            String recovery = invocation.suggestedNextAction() == null
                    ? argumentRecovery(toolName, invocation.error())
                    : invocation.suggestedNextAction();
            server.write(JsonRpcResponse.success(id,
                    commandResultAdapter.invalidArguments(invocation.error(), recovery)));
            return;
        }

        CommandResult result = commandExecution.execute(
                invocation.args(), new LinkedHashMap<String, String>(environment), invocation.stdin());
        McpInvocationContext context = McpInvocationContext.fromValidatedArguments(toolName, arguments);
        server.write(JsonRpcResponse.success(id, commandResultAdapter.adapt(result, context)));
    }

    private static String argumentRecovery(String toolName, String message) {
        return message + " Call the " + toolName + " MCP tool with the corrected arguments.";
    }
}
