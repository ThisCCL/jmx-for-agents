package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

class McpReferenceScopeTest {
    private static final String TOKEN = "abcdefghijklmnop";

    @ParameterizedTest(name = "{0}")
    @MethodSource("successfulResultScopes")
    void successfulResultsDescribeOnlyAlreadyEmittedReferenceCategories(
            String scenario, Map<String, Object> commandData, Map<String, Object> expectedScope) {
        String text = "receipt: unchanged\nref: " + TOKEN + "\n";
        Map<String, Object> result = new McpCommandResultAdapter().adapt(success(text, commandData));

        Map<String, Object> structured = mapping(result.get("structuredContent"));
        assertThat(mapping(structured.get("ref_scope"))).isEqualTo(expectedScope);
        Object expectedData = "read".equals(commandData.get("command"))
                ? new Yaml().load(text) : commandData;
        assertThat(structured.get("data")).isEqualTo(expectedData);
        assertThat(mapping(list(result.get("content")).get(0)).get("text")).isEqualTo(text);
        assertThat(McpJson.write(structured.get("ref_scope")))
                .doesNotContain(TOKEN, "fingerprint", "generation", "LRU", "canonical", "home", "refs");
    }

    @Test
    void referenceScopeDoesNotChangeWithRenderedOrCreatedReferenceCount() {
        Map<String, Object> commandData = data("read", null);
        Map<String, Object> small = new McpCommandResultAdapter().adapt(
                success("root:\n  ref: " + TOKEN + "\n", commandData));
        StringBuilder largeText = new StringBuilder("root:\n  children:\n");
        for (int index = 0; index < 100; index++) {
            largeText.append("    - ref: token").append(String.format("%011d", Integer.valueOf(index))).append("\n");
        }
        Map<String, Object> large = new McpCommandResultAdapter().adapt(success(largeText.toString(), commandData));

        String smallScope = McpJson.write(mapping(mapping(small.get("structuredContent")).get("ref_scope")));
        String largeScope = McpJson.write(mapping(mapping(large.get("structuredContent")).get("ref_scope")));
        assertThat(smallScope).isEqualTo(largeScope).hasSizeLessThan(100);
        assertThat(mapping(mapping(small.get("structuredContent")).get("data")))
                .isEqualTo(mapping(new Yaml().load("root:\n  ref: " + TOKEN + "\n")));
        assertThat(mapping(mapping(large.get("structuredContent")).get("data")))
                .isEqualTo(mapping(new Yaml().load(largeText.toString())));

        Map<String, Object> oneCreated = data("apply", "copy");
        Map<String, Object> manyCreated = data("apply", "copy");
        List<Object> createdRefs = new java.util.ArrayList<Object>();
        for (int index = 0; index < 100; index++) {
            createdRefs.add(createdRef("token" + String.format("%011d", Integer.valueOf(index))));
        }
        manyCreated.put("createdRefs", createdRefs);
        String oneCreatedScope = McpJson.write(mapping(mapping(
                new McpCommandResultAdapter().adapt(success("unchanged", oneCreated))
                        .get("structuredContent")).get("ref_scope")));
        String manyCreatedScope = McpJson.write(mapping(mapping(
                new McpCommandResultAdapter().adapt(success("unchanged", manyCreated))
                        .get("structuredContent")).get("ref_scope")));
        assertThat(oneCreatedScope).isEqualTo(manyCreatedScope).hasSizeLessThan(100);
    }

    private static Stream<Arguments> successfulResultScopes() {
        return Stream.of(
                Arguments.of("read", data("read", null), scope("source-bound", "none", "none")),
                Arguments.of("init", data("init", null), scope("none", "target-bound", "none")),
                Arguments.of("set", data("set", null), scope("none", "none", "none")),
                Arguments.of("apply dry-run", data("apply", "dry-run"), scope("none", "none", "none")),
                Arguments.of("apply in-place", data("apply", "in-place"), scope("none", "none", "target-bound")),
                Arguments.of("apply copy", data("apply", "copy"), scope("source-bound", "none", "target-bound")));
    }

    private static Map<String, Object> data(String command, String writeMode) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("command", command);
        data.put("format", "yaml");
        if (writeMode != null) {
            data.put("writeMode", writeMode);
            data.put("createdRefs", Arrays.<Object>asList(createdRef(TOKEN)));
        }
        return data;
    }

    private static Map<String, Object> createdRef(String ref) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("alias", "created");
        row.put("ref", ref);
        return row;
    }

    private static Map<String, Object> scope(String source, String target, String created) {
        Map<String, Object> scope = new LinkedHashMap<String, Object>();
        scope.put("source", source);
        scope.put("target", target);
        scope.put("created", created);
        return scope;
    }

    private static CommandResult success(final String text, final Map<String, Object> data) {
        return new CommandResult() {
            @Override public int exitCode() { return 0; }
            @Override public String textOutput() { return text; }
            @Override public Map<String, Object> structuredData() { return data; }
            @Override public List<CommandDiagnostic> diagnostics() {
                return Collections.emptyList();
            }
            @Override public String recoveryGuidance() { return null; }
        };
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
