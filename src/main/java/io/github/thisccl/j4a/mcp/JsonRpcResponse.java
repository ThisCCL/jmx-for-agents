package io.github.thisccl.j4a.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

final class JsonRpcResponse {
    private JsonRpcResponse() {
    }

    static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        return response;
    }
}
