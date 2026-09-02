package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class McpSessionStableRefsStdioIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void realStdioProcessKeepsRefsAcrossCallsAndRejectsThemAfterRestart() throws Exception {
        Path source = tempDir.resolve("session-source.jmx");
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        String configuredHome = System.getProperty("j4a.test.jmeterHome");
        Path home = configuredHome == null
                ? DefaultLocalProfileQaFixtures.fresh().localHome()
                : Paths.get(configuredHome);
        List<String> transcript = new ArrayList<String>();
        String retainedRef;

        try (StdioSession first = StdioSession.start()) {
            first.initialize();
            Map<String, Object> read = first.call(2, "read", arguments(source, home, null));
            retainedRef = componentReferences(text(read)).get(0);
            Map<String, Object> focused = first.call(3, "read", arguments(source, home, retainedRef));

            assertThat(read).containsEntry("isError", Boolean.FALSE);
            assertThat(focused).containsEntry("isError", Boolean.FALSE);
            assertThat(componentReferences(text(focused))).contains(retainedRef);
            transcript.add(sanitize("{\"server\":1,\"scenario\":\"read\",\"result\":"
                    + McpJson.write(read) + "}", source, home, retainedRef));
            transcript.add(sanitize("{\"server\":1,\"scenario\":\"focus\",\"result\":"
                    + McpJson.write(focused) + "}", source, home, retainedRef));
            first.shutdown();
        }

        try (StdioSession second = StdioSession.start()) {
            second.initialize();
            Map<String, Object> stale = second.call(4, "read", arguments(source, home, retainedRef));

            assertThat(stale).containsEntry("isError", Boolean.TRUE);
            Map<String, Object> staleStructured = mapping(stale.get("structuredContent"));
            assertThat(list(staleStructured.get("diagnostics"))).singleElement().satisfies(item ->
                    assertThat(mapping(item))
                            .containsEntry("code", "MCP_REF_NOT_FOUND")
                            .containsEntry("category", "usage")
                            .hasEntrySatisfying("suggestedNextAction", action -> assertThat(String.valueOf(action))
                                    .contains("same file", "same JMeter home", "fresh ref")));
            Map<String, Object> recovery = mapping(staleStructured.get("recovery"));
            assertThat(recovery).containsOnlyKeys("tool", "arguments").containsEntry("tool", "read");
            Map<String, Object> expectedRecoveryArguments = new java.util.LinkedHashMap<String, Object>();
            expectedRecoveryArguments.put("file", source.toString());
            expectedRecoveryArguments.put("jmeter_home", home.toString());
            assertThat(mapping(recovery.get("arguments")))
                    .containsExactlyEntriesOf(expectedRecoveryArguments);

            Map<String, Object> refreshed = second.call(
                    5, String.valueOf(recovery.get("tool")), mapping(recovery.get("arguments")));
            assertThat(refreshed).containsEntry("isError", Boolean.FALSE);
            assertThat(componentReferences(text(refreshed))).doesNotContain(retainedRef);
            transcript.add(sanitize("{\"server\":2,\"scenario\":\"restart-stale\",\"result\":"
                    + McpJson.write(stale) + "}", source, home, retainedRef));
            transcript.add(sanitize("{\"server\":2,\"scenario\":\"fresh-read\",\"result\":"
                    + McpJson.write(refreshed) + "}", source, home, retainedRef));
            second.shutdown();
        }

        Path qa = buildDirectory().resolve("qa/mcp-session-stable-component-refs");
        Files.createDirectories(qa);
        Path jsonl = qa.resolve("stdio-restart.jsonl");
        Files.write(jsonl, (String.join("\n", transcript) + "\n").getBytes(StandardCharsets.UTF_8));
        Path hashes = qa.resolve("SHA256SUMS");
        Files.write(hashes, (sha256(jsonl) + "  " + jsonl.getFileName() + "\n")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(jsonl).isNotEmptyFile();
        assertThat(hashes).isNotEmptyFile();
    }

    @Test
    void realStdioProcessReturnsOriginalReadRecoveryForEveryRemainingStaleCause() throws Exception {
        Path home = localHome();
        Path alternateHome = DefaultLocalProfileQaFixtures.fresh().localHome();
        byte[] fixture = Files.readAllBytes(Paths.get("src/test/resources/fixtures/simple-http.jmx"));
        Path wrongPathSource = writeFixture("wrong-path-source.jmx", fixture);
        Path wrongPathTarget = writeFixture("wrong-path-target.jmx", fixture);
        Path wrongHomeSource = writeFixture("wrong-home-source.jmx", fixture);
        Path externalSource = writeFixture("external-source.jmx", fixture);
        Path missingSource = writeFixture("missing-source.jmx", fixture);
        Path deletedComponentSource = writeFixture("deleted-component-source.jmx", fixture);
        Path copySource = writeFixture("copy-source.jmx", fixture);
        Path multiRefSource = writeFixture("multi-ref-source.jmx", fixture);

        try (StdioSession session = StdioSession.start()) {
            session.initialize();

            String wrongPathRef = firstReference(session.call(2, "read", arguments(wrongPathSource, home, null)));
            String wrongPathSpelling = spelling(wrongPathTarget);
            String wrongPathSourceHash = sha256(wrongPathSource);
            String wrongPathTargetHash = sha256(wrongPathTarget);
            int wrongPathCalls = session.toolCalls();
            Map<String, Object> wrongPath = session.call(3, "read",
                    arguments("path", wrongPathSpelling, home.toString(), wrongPathRef));
            assertSingleStaleInvocation(session, wrongPathCalls);
            Map<String, Object> wrongPathRecovery = assertRecovery(wrongPath, "path", wrongPathSpelling, home.toString());
            assertThat(sha256(wrongPathSource)).isEqualTo(wrongPathSourceHash);
            assertThat(sha256(wrongPathTarget)).isEqualTo(wrongPathTargetHash);
            assertRefStillResolves(session, 4, wrongPathSource, home, wrongPathRef);
            assertFreshRead(session, 5, wrongPathRecovery, wrongPathRef);

            String wrongHomeRef = firstReference(session.call(6, "read", arguments(wrongHomeSource, home, null)));
            String wrongHomeSpelling = spelling(alternateHome);
            String wrongHomeSourceHash = sha256(wrongHomeSource);
            int wrongHomeCalls = session.toolCalls();
            Map<String, Object> wrongHome = session.call(7, "read",
                    arguments("file", spelling(wrongHomeSource), wrongHomeSpelling, wrongHomeRef));
            assertSingleStaleInvocation(session, wrongHomeCalls);
            Map<String, Object> wrongHomeRecovery = assertRecovery(
                    wrongHome, "file", spelling(wrongHomeSource), wrongHomeSpelling);
            assertThat(sha256(wrongHomeSource)).isEqualTo(wrongHomeSourceHash);
            assertRefStillResolves(session, 8, wrongHomeSource, home, wrongHomeRef);
            assertFreshRead(session, 9, wrongHomeRecovery, wrongHomeRef);

            String externalRef = firstReference(session.call(10, "read", arguments(externalSource, home, null)));
            Files.write(externalSource, new String(fixture, StandardCharsets.UTF_8)
                    .replace("Synthetic Test Plan", "External Recovery Change").getBytes(StandardCharsets.UTF_8));
            String externalHash = sha256(externalSource);
            Path externalTarget = tempDir.resolve("external-must-not-write.jmx");
            int externalCalls = session.toolCalls();
            Map<String, Object> external = session.call(11, "set", staleSet(
                    "file", spelling(externalSource), home.toString(), externalRef, externalTarget));
            assertSingleStaleInvocation(session, externalCalls);
            Map<String, Object> externalRecovery = assertRecovery(
                    external, "file", spelling(externalSource), home.toString());
            assertThat(sha256(externalSource)).isEqualTo(externalHash);
            assertThat(externalTarget).doesNotExist();
            assertFreshRead(session, 12, externalRecovery, externalRef);

            String missingRef = firstReference(session.call(13, "read", arguments(missingSource, home, null)));
            Files.delete(missingSource);
            Path missingTarget = tempDir.resolve("missing-must-not-write.jmx");
            int missingCalls = session.toolCalls();
            Map<String, Object> missing = session.call(14, "set", staleSet(
                    "path", spelling(missingSource), home.toString(), missingRef, missingTarget));
            assertSingleStaleInvocation(session, missingCalls);
            Map<String, Object> missingRecovery = assertRecovery(
                    missing, "path", spelling(missingSource), home.toString());
            assertThat(missingSource).doesNotExist();
            assertThat(missingTarget).doesNotExist();
            Files.write(missingSource, fixture);
            assertFreshRead(session, 15, missingRecovery, missingRef);

            List<String> deletedComponentRefs = componentReferences(text(session.call(
                    16, "read", arguments(deletedComponentSource, home, null))));
            String deletedComponentRef = deletedComponentRefs.get(deletedComponentRefs.size() - 1);
            Map<String, Object> deleteArguments = documentArguments(
                    "path", spelling(deletedComponentSource), home.toString());
            deleteArguments.put("patchYaml", deletePatch(deletedComponentRef));
            deleteArguments.put("override", Boolean.TRUE);
            Map<String, Object> deleted = session.call(17, "apply", deleteArguments);
            assertThat(deleted).containsEntry("isError", Boolean.FALSE);
            Map<String, Object> deletedData = mapping(mapping(deleted.get("structuredContent")).get("data"));
            assertThat(list(deletedData.get("deletedRefs"))).contains(deletedComponentRef);
            String deletedComponentHash = sha256(deletedComponentSource);
            Path deletedComponentTarget = tempDir.resolve("deleted-component-must-not-write.jmx");
            int deletedComponentCalls = session.toolCalls();
            Map<String, Object> deletedComponent = session.call(18, "set", staleSet(
                    "path", spelling(deletedComponentSource), home.toString(), deletedComponentRef,
                    deletedComponentTarget));
            assertSingleStaleInvocation(session, deletedComponentCalls);
            Map<String, Object> deletedComponentRecovery = assertRecovery(
                    deletedComponent, "path", spelling(deletedComponentSource), home.toString());
            assertThat(sha256(deletedComponentSource)).isEqualTo(deletedComponentHash);
            assertThat(deletedComponentTarget).doesNotExist();
            assertFreshRead(session, 19, deletedComponentRecovery, deletedComponentRef);

            String copySourceRef = firstReference(session.call(20, "read", arguments(copySource, home, null)));
            Path copyTarget = tempDir.resolve("copy-target.jmx");
            Map<String, Object> copied = session.call(21, "set", staleSet(
                    "file", spelling(copySource), home.toString(), copySourceRef, copyTarget));
            assertThat(copied).containsEntry("isError", Boolean.FALSE);
            String copySourceHash = sha256(copySource);
            String copyTargetHash = sha256(copyTarget);
            int copyCalls = session.toolCalls();
            Map<String, Object> copyReuse = session.call(22, "read",
                    arguments("file", spelling(copyTarget), home.toString(), copySourceRef));
            assertSingleStaleInvocation(session, copyCalls);
            Map<String, Object> copyRecovery = assertRecovery(
                    copyReuse, "file", spelling(copyTarget), home.toString());
            assertThat(sha256(copySource)).isEqualTo(copySourceHash);
            assertThat(sha256(copyTarget)).isEqualTo(copyTargetHash);
            assertRefStillResolves(session, 23, copySource, home, copySourceRef);
            assertFreshRead(session, 24, copyRecovery, copySourceRef);

            List<String> multiRefs = componentReferences(text(session.call(25, "read", arguments(multiRefSource, home, null))));
            String validRef = multiRefs.get(0);
            String multiSourceHash = sha256(multiRefSource);
            int multiCalls = session.toolCalls();
            Map<String, Object> unavailableMultiRefApply = session.call(26, "apply", multiRefApply(
                    spelling(multiRefSource), home.toString(), validRef));
            assertSingleStaleInvocation(session, multiCalls);
            Map<String, Object> multiRecovery = assertRecovery(
                    unavailableMultiRefApply, "file", spelling(multiRefSource), home.toString());
            assertThat(sha256(multiRefSource)).isEqualTo(multiSourceHash);
            assertRefStillResolves(session, 27, multiRefSource, home, validRef);
            assertFreshRead(session, 28, multiRecovery, null);
            session.shutdown();
        }
    }

    private static Map<String, Object> arguments(Path source, Path home, String ref) {
        return arguments("file", source.toString(), home.toString(), ref);
    }

    private static Map<String, Object> arguments(String documentKey, String document, String home, String ref) {
        Map<String, Object> arguments = documentArguments(documentKey, document, home);
        arguments.put("depth", Integer.valueOf(5));
        if (ref != null) {
            arguments.put("ref", ref);
        }
        return arguments;
    }

    private static Map<String, Object> documentArguments(String documentKey, String document, String home) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put(documentKey, document);
        arguments.put("jmeter_home", home);
        return arguments;
    }

    private Map<String, Object> staleSet(
            String documentKey, String document, String home, String ref, Path target) {
        Map<String, Object> arguments = documentArguments(documentKey, document, home);
        arguments.put("locator", ref);
        arguments.put("property", Arrays.asList("TestElement.name"));
        arguments.put("value", "must-not-replay-or-leak");
        arguments.put("out", target.toString());
        arguments.put("forceOut", Boolean.TRUE);
        return arguments;
    }

    private static Map<String, Object> multiRefApply(String source, String home, String validRef) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", source);
        arguments.put("patchYaml", "changes:\n"
                + "  - set:\n"
                + "      ref: " + validRef + "\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          type: string\n"
                + "          value: first-must-not-write\n"
                + "  - set:\n"
                + "      ref: AAAAAAAAAAAAAAAA\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          type: string\n"
                + "          value: second-must-not-write\n");
        arguments.put("dryRun", Boolean.TRUE);
        arguments.put("jmeter_home", home);
        return arguments;
    }

    private static String deletePatch(String ref) {
        return "changes:\n"
                + "  - delete:\n"
                + "      ref: " + ref + "\n";
    }

    private static Map<String, Object> assertRecovery(
            Map<String, Object> result, String documentKey, String document, String home) {
        assertThat(result).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(result.get("structuredContent"));
        assertThat(list(structured.get("diagnostics"))).singleElement().satisfies(diagnostic ->
                assertThat(mapping(diagnostic))
                        .containsEntry("code", "MCP_REF_NOT_FOUND")
                        .containsEntry("category", "usage")
                        .containsEntry("message", "The session component ref is unavailable. It may be mistyped, deleted, "
                                + "refreshed after an external change, evicted, or lost after MCP/runtime restart.")
                        .containsEntry("suggestedNextAction", "Call the read MCP tool with the same file and same JMeter home "
                                + "(jmeter_home) to obtain fresh refs, then use the returned ref field in a new MCP tool call."));
        assertThat(structured.get("recoveryGuidance")).isEqualTo(
                "Call the read MCP tool with the same file and same JMeter home (jmeter_home) to obtain fresh refs, "
                        + "then use the returned ref field in a new MCP tool call.");
        Map<String, Object> recovery = mapping(structured.get("recovery"));
        Map<String, Object> expectedArguments = new LinkedHashMap<String, Object>();
        expectedArguments.put(documentKey, document);
        expectedArguments.put("jmeter_home", home);
        assertThat(recovery).containsOnlyKeys("tool", "arguments").containsEntry("tool", "read");
        assertThat(mapping(recovery.get("arguments"))).containsExactlyEntriesOf(expectedArguments)
                .doesNotContainKeys("ref", "locator", "patchYaml", "patchFile", "value", "payload", "out",
                        "override", "forceOut", "canonical", "internal");
        return recovery;
    }

    private static void assertSingleStaleInvocation(StdioSession session, int callsBefore) {
        assertThat(session.toolCalls()).isEqualTo(callsBefore + 1);
    }

    private static void assertRefStillResolves(
            StdioSession session, int id, Path source, Path home, String ref) throws IOException {
        assertThat(session.call(id, "read", arguments(source, home, ref))).containsEntry("isError", Boolean.FALSE);
    }

    private static void assertFreshRead(
            StdioSession session, int id, Map<String, Object> recovery, String unavailableRef) throws IOException {
        Map<String, Object> fresh = session.call(id, String.valueOf(recovery.get("tool")), mapping(recovery.get("arguments")));
        assertThat(fresh).containsEntry("isError", Boolean.FALSE);
        assertThat(componentReferences(text(fresh))).isNotEmpty();
        if (unavailableRef != null) {
            assertThat(componentReferences(text(fresh))).doesNotContain(unavailableRef);
        }
    }

    private Path writeFixture(String name, byte[] fixture) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, fixture);
        return path;
    }

    private static String spelling(Path path) {
        return path.getParent().resolve(".").resolve(path.getFileName()).toString();
    }

    private static String firstReference(Map<String, Object> result) {
        assertThat(result).containsEntry("isError", Boolean.FALSE);
        return componentReferences(text(result)).get(0);
    }

    private static Path localHome() throws IOException {
        String configuredHome = System.getProperty("j4a.test.jmeterHome");
        return configuredHome == null
                ? DefaultLocalProfileQaFixtures.fresh().localHome()
                : Paths.get(configuredHome);
    }

    private static String text(Map<String, Object> result) {
        return String.valueOf(mapping(list(result.get("content")).get(0)).get("text"));
    }

    private static List<String> componentReferences(String yamlText) {
        List<String> references = new ArrayList<String>();
        collectReferences(new Yaml().load(yamlText), references);
        return references;
    }

    private static void collectReferences(Object value, List<String> references) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("ref".equals(entry.getKey()) && entry.getValue() instanceof String) {
                    references.add((String) entry.getValue());
                }
                collectReferences(entry.getValue(), references);
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectReferences(item, references);
            }
        }
    }

    private static Path buildDirectory() throws Exception {
        Path classes = Paths.get(McpSessionStableRefsStdioIntegrationTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return classes.getParent().getParent().getParent();
    }

    private static String sanitize(String line, Path source, Path home, String retainedRef) {
        return line.replace(source.toString(), "<SOURCE>")
                .replace(home.toString(), "<JMETER_HOME>")
                .replace(retainedRef, "<RETAINED_REF>")
                .replaceAll("(?<=ref: )[A-Za-z0-9_-]{16}", "<SESSION_REF>");
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(path);
        byte[] hash = digest.digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : hash) {
            text.append(String.format("%02x", Integer.valueOf(value & 0xff)));
        }
        return text.toString();
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

    private static final class StdioSession implements AutoCloseable {
        private final Process process;
        private final PrintWriter requests;
        private final BufferedReader responses;
        private int toolCalls;

        private StdioSession(Process process) {
            this.process = process;
            this.requests = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
            this.responses = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        private static StdioSession start() throws IOException {
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    J4aMcpServer.class.getName()).start();
            return new StdioSession(process);
        }

        private void initialize() throws IOException {
            requests.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"stdio-stable-ref-test\",\"version\":\"1\"}}}");
            assertThat(responses.readLine()).isNotNull();
            requests.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        }

        private Map<String, Object> call(int id, String tool, Map<String, Object> arguments) throws IOException {
            Map<String, Object> params = new java.util.LinkedHashMap<String, Object>();
            params.put("name", tool);
            params.put("arguments", arguments);
            Map<String, Object> request = new java.util.LinkedHashMap<String, Object>();
            request.put("jsonrpc", "2.0");
            request.put("id", Integer.valueOf(id));
            request.put("method", "tools/call");
            request.put("params", params);
            toolCalls++;
            requests.println(McpJson.write(request));
            return mapping(mapping(new Yaml().load(responses.readLine())).get("result"));
        }

        private int toolCalls() {
            return toolCalls;
        }

        private void shutdown() throws IOException {
            requests.println("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\",\"params\":{}}");
            assertThat(responses.readLine()).isNotNull();
        }

        @Override
        public void close() throws Exception {
            requests.close();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("MCP stdio subprocess did not exit");
            }
            if (process.exitValue() != 0) {
                throw new IOException("MCP stdio subprocess failed: " + readAll(process.getErrorStream()));
            }
        }

        private static String readAll(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
