package io.github.thisccl.j4a.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class J4aMcpServer {
    private J4aMcpServer() {
    }

    public static void main(String[] args) {
        System.exit(run(System.in, utf8PrintStream(System.out), utf8PrintStream(System.err), args));
    }

    private static PrintStream utf8PrintStream(PrintStream stream) {
        try {
            return new PrintStream(stream, true, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 output encoding is unavailable.", exception);
        }
    }

    public static int run(InputStream input, PrintStream stdout, PrintStream stderr, String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            stdout.println("Usage: j4a-mcp");
            stdout.println("Starts the j4a MCP stdio server. stdout is reserved for JSON-RPC protocol messages.");
            return 0;
        }
        if (args.length > 0) {
            stderr.println("Unsupported MCP server argument. Pass --help for usage.");
            return 2;
        }

        try {
            return new McpStdioServer(input, stdout).run();
        } catch (IOException exception) {
            stderr.println("MCP server I/O failure: " + exception.getMessage());
            return 1;
        }
    }
}
