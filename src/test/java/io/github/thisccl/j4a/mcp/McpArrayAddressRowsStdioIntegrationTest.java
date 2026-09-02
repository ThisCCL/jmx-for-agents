package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class McpArrayAddressRowsStdioIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void packagedSessionKeepsArrayAddressesAndIncrementalRowsLiveAndSafe() throws Exception {
        Path home = io.github.thisccl.j4a.TestJMeterRuntime.home().toRealPath();
        Path scalarSource = copy("src/test/resources/fixtures/simple-http.jmx", "scalar-source.jmx");
        Path rowSource = copy(
                "src/test/resources/fixtures/structured-collection-adapters/local-profile-input.jmx",
                "row-source.jmx");
        String scalarHash = sha256(scalarSource);
        String rowHash = sha256(rowSource);
        List<String> transcript = new ArrayList<String>();

        Path scalarOutput = tempDir.resolve("scalar-output.jmx");
        Path appendedOutput = tempDir.resolve("appended-output.jmx");
        Path finalOutput = tempDir.resolve("final-output.jmx");
        Path initOutput = tempDir.resolve("initialized.jmx");
        Path stderr = tempDir.resolve("mcp-stderr.log");
        try (McpPackagedStdioSession session = McpPackagedStdioSession.start(stderr)) {
            record(transcript, "initialize", session.initialize());
            Map<String, Object> toolsList = session.toolsList(2);
            record(transcript, "tools-list", toolsList);
            assertIncrementalToolContract(toolsList);

            Map<String, Object> init = session.call(3, "init", map(
                    "out", initOutput.toString(), "name", "MCP Array Rows",
                    "threadGroupName", "MCP Thread Group", "jmeter_home", home.toString()));
            assertSuccess(init);
            assertThat(initOutput).isRegularFile();
            record(transcript, "init", init);

            Map<String, Object> scalarRead = session.call(4, "read", readArguments(scalarSource, home));
            String scalarRef = refFor(scalarRead, "Synthetic HTTP Request");
            record(transcript, "read-scalar", scalarRead);
            Map<String, Object> set = session.call(5, "set", map(
                    "file", scalarSource.toString(), "locator", scalarRef,
                    "property", Collections.<Object>singletonList("HTTPSampler.path"),
                    "value", "/mcp-array", "out", scalarOutput.toString(),
                    "jmeter_home", home.toString()));
            Map<String, Object> setData = assertSuccess(set);
            assertThat(mapping(setData.get("changedProperty")))
                    .containsEntry("property", Collections.<Object>singletonList("HTTPSampler.path"));
            assertThat(sha256(scalarSource)).isEqualTo(scalarHash);
            record(transcript, "set-array", set);
            Map<String, Object> setRead = session.call(6, "read", readArguments(scalarOutput, home));
            assertThat(propertyFor(setRead, "Synthetic HTTP Request", "HTTPSampler.path"))
                    .containsEntry("property", Collections.<Object>singletonList("HTTPSampler.path"))
                    .containsEntry("type", "string")
                    .containsEntry("value", "/mcp-array");
            record(transcript, "read-set-output", setRead);

            Map<String, Object> components = session.call(7, "components", map(
                    "category", "sampler", "jmeter_home", home.toString()));
            assertSuccess(components);
            record(transcript, "components", components);

            Map<String, Object> rowRead = session.call(8, "read", readArguments(rowSource, home));
            String rowRef = refFor(rowRead, "Contract Headers");
            record(transcript, "read-row-source", rowRead);
            Map<String, Object> focusedRows = session.call(9, "read", map(
                    "file", rowSource.toString(), "ref", rowRef, "depth", Integer.valueOf(5),
                    "properties", "all", "jmeter_home", home.toString()));
            assertSuccess(focusedRows);
            Map<String, Object> emptyRows = propertyFor(rowRead, "Contract Headers", "HeaderManager.headers");
            assertThat(emptyRows).containsEntry("type", "rows");
            assertThat(mapping(emptyRows.get("value"))).hasEntrySatisfying(
                    "rows", value -> assertThat(list(value)).isEmpty());
            record(transcript, "read-empty-rows", focusedRows);

            Map<String, Object> append = session.call(10, "apply", applyArguments(
                    rowSource, appendedOutput, home, appendPatch(rowRef)));
            assertReceipt(append, 1);
            assertThat(sha256(rowSource)).isEqualTo(rowHash);
            record(transcript, "append-empty-row", append);

            Map<String, Object> appendedRead = session.call(11, "read", readArguments(appendedOutput, home));
            String appendedRef = refFor(appendedRead, "Contract Headers");
            assertThat(rowValues(appendedRead)).containsExactly(row("X-Trace", "one"));
            record(transcript, "read-appended", appendedRead);
            Map<String, Object> ordered = session.call(12, "apply", applyArguments(
                    appendedOutput, finalOutput, home, insertRemovePatch(appendedRef)));
            assertReceipt(ordered, 2);
            record(transcript, "insert-remove", ordered);
            Map<String, Object> finalRead = session.call(13, "read", readArguments(finalOutput, home));
            assertThat(rowValues(finalRead)).containsExactly(row("X-First", "zero"));
            record(transcript, "read-final", finalRead);

            Map<String, Object> legacy = assertRejectedOnce(session, 14, applyArguments(
                    rowSource, tempDir.resolve("legacy-output.jmx"), home, stringAddressPatch(rowRef)));
            record(transcript, "legacy-string-address", legacy);
            assertThat(tempDir.resolve("legacy-output.jmx")).doesNotExist();
            assertThat(sha256(rowSource)).isEqualTo(rowHash);
            Map<String, Object> invalidIndex = assertRejectedOnce(session, 15, applyArguments(
                    appendedOutput, tempDir.resolve("invalid-index-output.jmx"), home,
                    invalidIndexPatch(appendedRef)));
            record(transcript, "invalid-index", invalidIndex);
            assertThat(tempDir.resolve("invalid-index-output.jmx")).doesNotExist();

            int beforeUnavailable = session.requestCount();
            Map<String, Object> unavailable = session.call(16, "set", map(
                    "file", scalarSource.toString(), "locator", "AAAAAAAAAAAAAAAA",
                    "property", Collections.<Object>singletonList("HTTPSampler.path"),
                    "value", "/must-not-replay", "out", tempDir.resolve("stale-output.jmx").toString(),
                    "jmeter_home", home.toString()));
            assertThat(session.requestCount()).isEqualTo(beforeUnavailable + 1);
            assertError(unavailable, "MCP_REF_NOT_FOUND");
            assertThat(tempDir.resolve("stale-output.jmx")).doesNotExist();
            assertThat(sha256(scalarSource)).isEqualTo(scalarHash);
            record(transcript, "unavailable-ref", unavailable);
            Map<String, Object> recovered = session.call(17, "read", readArguments(scalarSource, home));
            assertSuccess(recovered);
            record(transcript, "read-after-failures", recovered);
            session.shutdown();
        }
        assertThat(stderr).hasContent("");
        writeEvidence(transcript, home);
    }

    private static Map<String, Object> assertRejectedOnce(
            McpPackagedStdioSession session, int id, Map<String, Object> arguments) throws Exception {
        int before = session.requestCount();
        Map<String, Object> rejected = session.call(id, "apply", arguments);
        assertThat(session.requestCount()).isEqualTo(before + 1);
        assertThat(rejected).containsEntry("isError", Boolean.TRUE);
        assertThat(mapping(rejected.get("structuredContent")))
                .hasEntrySatisfying("diagnostics", value -> assertThat(list(value)).isNotEmpty());
        return rejected;
    }

    private static void assertIncrementalToolContract(Map<String, Object> response) {
        String rendered = McpJson.write(response);
        assertThat(rendered.length()).isLessThanOrEqualTo(40000);
        Map<String, Object> result = mapping(response.get("result"));
        for (Object item : list(result.get("tools"))) {
            Map<String, Object> tool = mapping(item);
            String description = String.valueOf(tool.get("description"));
            assertThat(description.getBytes(StandardCharsets.UTF_8).length).isLessThan(4096);
            if ("apply".equals(tool.get("name"))) {
                assertThat(description).contains("append", "insert", "remove", "scalar", "array");
            }
        }
    }

    private static Map<String, Object> assertSuccess(Map<String, Object> result) {
        assertThat(result).containsEntry("isError", Boolean.FALSE);
        Map<String, Object> structured = mapping(result.get("structuredContent"));
        assertThat(structured).containsEntry("exitStatus", Integer.valueOf(0));
        assertThat(list(structured.get("diagnostics"))).isEmpty();
        return mapping(structured.get("data"));
    }

    private static void assertReceipt(Map<String, Object> result, int appliedCount) {
        Map<String, Object> data = assertSuccess(result);
        assertThat(data)
                .containsEntry("command", "apply")
                .containsEntry("writeMode", "copy")
                .containsEntry("appliedCount", Integer.valueOf(appliedCount))
                .containsEntry("createdRefs", Collections.emptyList())
                .containsEntry("deletedRefs", Collections.emptyList());
        assertThat(mapping(new Yaml().load(text(result)))).isEqualTo(data);
    }

    private static void assertError(Map<String, Object> result, String code) {
        assertThat(result).containsEntry("isError", Boolean.TRUE);
        assertThat(list(mapping(result.get("structuredContent")).get("diagnostics")))
                .anySatisfy(value -> assertThat(mapping(value)).containsEntry("code", code));
    }

    private static Map<String, Object> readArguments(Path source, Path home) {
        return map("file", source.toString(), "depth", Integer.valueOf(5),
                "properties", "all", "jmeter_home", home.toString());
    }

    private static Map<String, Object> applyArguments(Path source, Path output, Path home, String patch) {
        return map("file", source.toString(), "patchYaml", patch, "out", output.toString(),
                "jmeter_home", home.toString());
    }

    private static String appendPatch(String ref) {
        return "changes:\n  - append:\n      ref: " + ref
                + "\n      property: [HeaderManager.headers]\n"
                + "      row: {Header.name: X-Trace, Header.value: one}\n";
    }

    private static String insertRemovePatch(String ref) {
        return "changes:\n  - insert:\n      ref: " + ref
                + "\n      property: [HeaderManager.headers]\n      index: 0\n"
                + "      row: {Header.name: X-First, Header.value: zero}\n"
                + "  - remove:\n      ref: " + ref
                + "\n      property: [HeaderManager.headers]\n      index: 1\n";
    }

    private static String stringAddressPatch(String ref) {
        return "changes:\n  - append:\n      ref: " + ref
                + "\n      property: HeaderManager.headers\n"
                + "      row: {Header.name: rejected, Header.value: rejected}\n";
    }

    private static String invalidIndexPatch(String ref) {
        return "changes:\n  - remove:\n      ref: " + ref
                + "\n      property: [HeaderManager.headers]\n      index: 99\n";
    }

    private Path copy(String source, String name) throws Exception {
        Path target = tempDir.resolve(name);
        Files.copy(Paths.get(source), target);
        return target;
    }

    private static String refFor(Map<String, Object> result, String name) {
        assertSuccess(result);
        Map<String, Object> node = nodeNamed(new Yaml().load(text(result)), name);
        assertThat(node).isNotNull();
        assertThat(node.get("ref")).isInstanceOf(String.class);
        return String.valueOf(node.get("ref"));
    }

    private static Map<String, Object> propertyFor(
            Map<String, Object> result, String nodeName, String propertyName) {
        assertSuccess(result);
        Map<String, Object> node = nodeNamed(new Yaml().load(text(result)), nodeName);
        assertThat(node).isNotNull();
        for (Object item : list(node.get("properties"))) {
            Map<String, Object> property = mapping(item);
            if (Collections.<Object>singletonList(propertyName).equals(property.get("property"))) {
                return property;
            }
        }
        throw new AssertionError("missing property " + propertyName + " on " + nodeName);
    }

    private static List<Map<String, Object>> rowValues(Map<String, Object> result) {
        Map<String, Object> property = propertyFor(result, "Contract Headers", "HeaderManager.headers");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Object item : list(mapping(property.get("value")).get("rows"))) rows.add(mapping(item));
        return rows;
    }

    private static Map<String, Object> row(String name, String value) {
        return map("Header.name", name, "Header.value", value);
    }

    private static Map<String, Object> nodeNamed(Object value, String name) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> candidate = mapping(value);
            if (name.equals(candidate.get("name"))) return candidate;
            for (Object child : candidate.values()) {
                Map<String, Object> match = nodeNamed(child, name);
                if (match != null) return match;
            }
        } else if (value instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) value) {
                Map<String, Object> match = nodeNamed(child, name);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void record(List<String> transcript, String scenario, Map<String, Object> response) {
        transcript.add(McpJson.write(map("scenario", scenario, "response", response)));
    }

    private void writeEvidence(List<String> transcript, Path home) throws Exception {
        Path qa = Paths.get(System.getProperty("user.dir"), "build", "qa", "mcp-array-rows");
        Files.createDirectories(qa);
        Path jsonl = qa.resolve("stdio.jsonl");
        List<String> sanitized = new ArrayList<String>();
        for (String line : transcript) sanitized.add(sanitize(line).replace(home.toString(), "<JMETER_HOME>"));
        Files.write(jsonl, (String.join("\n", sanitized) + "\n").getBytes(StandardCharsets.UTF_8));
        Path manifest = qa.resolve("SHA256SUMS");
        Files.write(manifest, (sha256(jsonl) + "  stdio.jsonl\n").getBytes(StandardCharsets.UTF_8));
        assertThat(jsonl).isNotEmptyFile();
        assertThat(manifest).hasContent(sha256(jsonl) + "  stdio.jsonl\n");
    }

    private String sanitize(String value) {
        return value.replace(tempDir.toString(), "<QA_TEMP>")
                .replaceAll("(\\\"ref\\\"\\s*:\\s*\\\")[A-Za-z0-9_-]{16}(\\\")", "$1<SESSION_REF>$2")
                .replaceAll("(\\\"locator\\\"\\s*:\\s*\\\")[A-Za-z0-9_-]{16}(\\\")", "$1<SESSION_REF>$2")
                .replaceAll("(ref: )[A-Za-z0-9_-]{16}", "$1<SESSION_REF>");
    }

    private static String text(Map<String, Object> result) {
        return String.valueOf(mapping(list(result.get("content")).get(0)).get("text"));
    }

    private static String sha256(Path path) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder value = new StringBuilder();
        for (byte item : hash) value.append(String.format("%02x", Integer.valueOf(item & 0xff)));
        return value.toString();
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
