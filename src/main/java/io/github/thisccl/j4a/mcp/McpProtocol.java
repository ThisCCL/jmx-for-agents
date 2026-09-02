package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.VersionInfo;
import java.util.LinkedHashMap;
import java.util.Map;

final class McpProtocol {
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private McpProtocol() {
    }

    static Map<String, Object> initializeResult(Object paramsValue) {
        String protocolVersion = PROTOCOL_VERSION;
        if (paramsValue instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) paramsValue;
            Object requestedVersion = params.get("protocolVersion");
            if (requestedVersion instanceof String && !((String) requestedVersion).isEmpty()) {
                protocolVersion = (String) requestedVersion;
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", protocolVersion);
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        Map<String, Object> tools = new LinkedHashMap<String, Object>();
        tools.put("listChanged", Boolean.FALSE);
        capabilities.put("tools", tools);
        result.put("capabilities", capabilities);
        Map<String, Object> serverInfo = new LinkedHashMap<String, Object>();
        serverInfo.put("name", "j4a");
        serverInfo.put("version", VersionInfo.version());
        result.put("serverInfo", serverInfo);
        return result;
    }

    static Map<String, Object> toolsListResult() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tools", McpTools.list());
        return result;
    }
}
