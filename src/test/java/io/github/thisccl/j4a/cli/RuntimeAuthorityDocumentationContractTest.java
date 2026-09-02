package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class RuntimeAuthorityDocumentationContractTest {
    private static final Path CANONICAL_GUIDANCE = Paths.get(
            "src/main/resources/io/github/thisccl/j4a/guidance/agent-guidance.json");

    @Test
    void skillFrontmatterKeepsTheJmeterJmxRoutingTrigger() throws Exception {
        Map<String, Object> frontmatter = frontmatter(read("skills/j4a-master/SKILL.md"));

        assertThat(frontmatter.get("name")).isEqualTo("j4a-master");
        assertThat(String.valueOf(frontmatter.get("description"))).matches("(?s).*\\bJMeter\\b.*\\.jmx\\b.*");
    }

    @Test
    void canonicalGuidanceMatchesPackagedSkillOwnershipAndPublicReadmeRoutesToIt() throws Exception {
        Map<String, Object> guidance = canonicalGuidance();
        String readme = read("README.md");
        String skill = read("skills/j4a-master/SKILL.md");
        String collectionsReference = read("skills/j4a-master/references/structured-collections.md");
        String writeModesReference = read("skills/j4a-master/references/write-modes.md");

        assertThat(readme).containsPattern("\\[[^\\]]+\\]\\(skills/j4a-master/SKILL\\.md\\)");

        assertThat(skill)
                .contains("live MCP tool schema")
                .contains("same file and JMeter home")
                .contains("`MCP_REF_NOT_FOUND`: fresh same-file/same-home `read`")
                .contains("Candidate verification failure: preserve the current target");
        assertThat(writeModesReference)
                .contains("`override: true`")
                .contains("`forceOut: true`")
                .contains("existing different output")
                .contains("same-file `out`");
        assertThat(collectionsReference)
                .contains(string(guidance, "generic_collection_example"))
                .contains(string(guidance, "semantic_row_example"));

        Map<String, Object> recovery = recovery(guidance);
        assertThat(string(recovery, "invalid_collection_shape"))
                .contains("\"tool\":\"read\"", "\"properties\":\"writable\"");
        assertThat(string(recovery, "same_path_write")).contains("j4a set", "--override");
        assertThat(string(recovery, "existing_target")).contains("--out", "--force-out");
        assertThat(string(recovery, "stale_mcp_ref"))
                .contains("\"tool\":\"read\"", "\"jmeter_home\"");

        Map<String, Object> semanticRow = semanticRow(guidance);

        Map<String, Object> collection = codeBlockOfType(collectionsReference, "collection");
        Map<String, Object> collectionValue = map(collection, "value");
        assertThat(collectionValue)
                .containsEntry("presence", "present")
                .containsEntry("property_class", "org.apache.jmeter.testelement.property.CollectionProperty");
        assertThat(collectionValue.get("items")).isInstanceOf(List.class);

        Map<String, Object> rows = codeBlockOfType(collectionsReference, "rows");
        assertThat(rows).isEqualTo(semanticRow);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> frontmatter(String markdown) {
        Matcher matcher = Pattern.compile("(?s)^---\\R(.*?)\\R---").matcher(markdown);
        assertThat(matcher.find()).isTrue();
        Object parsed = new Yaml().load(matcher.group(1));
        assertThat(parsed).isInstanceOf(Map.class);
        return (Map<String, Object>) parsed;
    }

    private static String read(String path) throws Exception {
        String documentationRoot = System.getenv("J4A_DOCUMENTATION_ROOT");
        return new String(Files.readAllBytes(
                documentationRoot == null ? Paths.get(path) : Paths.get(documentationRoot).resolve(path)),
                StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> canonicalGuidance() throws Exception {
        assertThat(CANONICAL_GUIDANCE).exists();
        try (InputStream input = Files.newInputStream(CANONICAL_GUIDANCE)) {
            Object parsed = new Yaml().load(input);
            assertThat(parsed).isInstanceOf(Map.class);
            return (Map<String, Object>) parsed;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recovery(Map<String, Object> guidance) {
        return (Map<String, Object>) guidance.get("recovery_templates");
    }

    private static String string(Map<String, Object> values, String key) {
        return String.valueOf(values.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> semanticRow(Map<String, Object> guidance) {
        Object parsed = new Yaml().load(string(guidance, "semantic_row_example"));
        assertThat(parsed).isInstanceOf(Map.class);
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> codeBlockOfType(String markdown, String type) {
        Matcher matcher = Pattern.compile("(?s)```json\\R(.*?)\\R```").matcher(markdown);
        while (matcher.find()) {
            Object parsed = new Yaml().load(matcher.group(1));
            if (parsed instanceof Map && type.equals(String.valueOf(((Map<?, ?>) parsed).get("type")))) {
                return (Map<String, Object>) parsed;
            }
        }
        throw new IllegalStateException("missing " + type + " example");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        assertThat(parent.get(key)).isInstanceOf(Map.class);
        return (Map<String, Object>) parent.get(key);
    }
}
