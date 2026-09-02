package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.list;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.mapping;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMainWithLocalRuntime;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class MainApplyRowMutationTest {
    private static final String HEADER_REF = "jmx_3a211baa6d71";
    private static final String ROOT_REF = "jmx_d62893619b1f";

    @TempDir
    Path tempDir;

    @Test
    void committedFixturesAppendInsertAndRemoveRowsInDeclarationOrder() throws IOException {
        Path source = tempDir.resolve("header-source.jmx");
        Path appended = tempDir.resolve("appended.jmx");
        Path inserted = tempDir.resolve("inserted.jmx");
        Path removed = tempDir.resolve("removed.jmx");
        Files.copy(fixture("array-address/empty-header-manager.jmx"), source);

        assertReceipt(applyFixture(source, appended, "append-header.yaml"), 1);
        assertRows(appended, "appended", "one");
        assertValid(appended);

        assertReceipt(applyFixture(appended, inserted, "insert-header.yaml"), 1);
        assertRows(inserted, "inserted", "zero", "appended", "one");
        assertValid(inserted);

        assertReceipt(applyFixture(inserted, removed, "remove-header.yaml"), 1);
        assertRows(removed, "appended", "one");
        assertValid(removed);
    }

    @Test
    void combinedHeaderRowPatchAppliesEveryChangeAndKeepsTheExpectedRow() throws IOException {
        Path source = sourceCopy("combined-header-source.jmx");
        Path output = tempDir.resolve("combined-header-output.jmx");
        String patch = "changes:\n"
                + append(HEADER_REF, "X-First", "first")
                + append(HEADER_REF, "discarded", "discarded")
                + remove(HEADER_REF, 1);

        CliTestResult result = runMainWithLocalRuntime(patch,
                "apply", source.toString(), "--patch", "-", "--out", output.toString());

        assertReceipt(result, 3);
        assertRows(output, "X-First", "first");
        assertValid(output);
    }

    @Test
    void onePatchUsesEveryValidBoundaryAndCountsEachIncrementalCard() throws IOException {
        Path source = sourceCopy("boundary-source.jmx");
        Path output = tempDir.resolve("boundary-output.jmx");
        String patch = "changes:\n"
                + append(HEADER_REF, "middle", "one")
                + insert(HEADER_REF, 0, "first", "zero")
                + insert(HEADER_REF, 2, "last", "two")
                + remove(HEADER_REF, 2)
                + remove(HEADER_REF, 0);

        CliTestResult result = runMainWithLocalRuntime(patch,
                "apply", source.toString(), "--patch", "-", "--out", output.toString());

        assertReceipt(result, 5);
        assertRows(output, "middle", "one");
        assertValid(output);
    }

    @Test
    void aliasReuseAndDryRunUseTheSameRuntimeProvenRowContract() throws IOException {
        Path source = sourceCopy("alias-source.jmx");
        Path aliasOutput = tempDir.resolve("alias-output.jmx");
        String aliasPatch = "changes:\n"
                + "  - add:\n"
                + "      parent: " + ROOT_REF + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.gui.HeaderPanel\n"
                + "      as: added_headers\n"
                + append("$added_headers", "alias-row", "alias-value");
        CliTestResult aliasResult = runMainWithLocalRuntime(aliasPatch,
                "apply", source.toString(), "--patch", "-", "--out", aliasOutput.toString());
        assertReceipt(aliasResult, 2);
        assertThat(read(aliasOutput).stdout()).contains("Header.name: alias-row", "Header.value: alias-value");

        byte[] sourceBefore = Files.readAllBytes(source);
        Path ignored = tempDir.resolve("ignored-existing.jmx");
        byte[] sentinel = "ignored".getBytes(StandardCharsets.UTF_8);
        Files.write(ignored, sentinel);
        CliTestResult dryRun = runMainWithLocalRuntime("",
                "apply", source.toString(), "--patch",
                fixture("array-address/append-header.yaml").toString(),
                "--dry-run", "--out", ignored.toString());
        assertReceipt(dryRun, 1);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(ignored)).containsExactly(sentinel);
    }

    @Test
    void rejectedLegacyAndInvalidFinalCardsPreserveEveryFile() throws IOException {
        Path source = sourceCopy("failure-source.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        Path legacyOutput = tempDir.resolve("legacy-output.jmx");

        CliTestResult legacy = runMainWithLocalRuntime("",
                "apply", source.toString(), "--patch",
                fixture("array-address/legacy-string-address.yaml").toString(),
                "--out", legacyOutput.toString());

        assertThat(legacy.exitCode()).isEqualTo(2);
        assertThat(legacy.stdout()).isEmpty();
        assertThat(legacy.stderr()).contains("PATCH_PARSE_ERROR", "non-empty scalar array");
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(legacyOutput).doesNotExist();

        Path existingOutput = tempDir.resolve("existing-output.jmx");
        byte[] sentinel = "unchanged-target".getBytes(StandardCharsets.UTF_8);
        Files.write(existingOutput, sentinel);
        String invalidFinal = "changes:\n"
                + append(HEADER_REF, "must-rollback", "one")
                + insert(HEADER_REF, 2, "invalid", "two");
        CliTestResult invalid = runMainWithLocalRuntime(invalidFinal,
                "apply", source.toString(), "--patch", "-",
                "--out", existingOutput.toString(), "--force-out");

        assertThat(invalid.exitCode()).isNotZero();
        assertThat(invalid.stdout()).isEmpty();
        assertThat(invalid.stderr()).contains("Insert index 2", "0 to 1");
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(existingOutput)).containsExactly(sentinel);
    }

    private CliTestResult applyFixture(Path source, Path output, String patchName) {
        return runMainWithLocalRuntime("", "apply", source.toString(), "--patch",
                fixture("array-address/" + patchName).toString(), "--out", output.toString());
    }

    private Path sourceCopy(String name) throws IOException {
        Path source = tempDir.resolve(name);
        Files.copy(fixture("array-address/empty-header-manager.jmx"), source);
        return source;
    }

    private void assertValid(Path path) {
        CliTestResult result = runMainWithLocalRuntime("", "validate", path.toString());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
    }

    private void assertRows(Path path, String... flattenedNameValues) {
        CliTestResult result = read(path);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        Map<String, Object> property = headerProperty(new Yaml().load(result.stdout()));
        assertThat(property.get("property"))
                .isEqualTo(Collections.<Object>singletonList("HeaderManager.headers"));
        List<Object> rows = list(mapping(property.get("value")).get("rows"));
        List<String> actual = new ArrayList<String>();
        for (Object row : rows) {
            Map<String, Object> fields = mapping(row);
            actual.add(String.valueOf(fields.get("Header.name")));
            actual.add(String.valueOf(fields.get("Header.value")));
        }
        assertThat(actual).containsExactly(flattenedNameValues);
    }

    private CliTestResult read(Path path) {
        return runMainWithLocalRuntime("", "read", path.toString(),
                "--depth", "4", "--properties", "all");
    }

    private static Map<String, Object> headerProperty(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = mapping(value);
            if (Collections.<Object>singletonList("HeaderManager.headers").equals(map.get("property"))) {
                return map;
            }
            for (Object child : map.values()) {
                Map<String, Object> found = headerPropertyOrNull(child);
                if (found != null) return found;
            }
        }
        throw new AssertionError("HeaderManager.headers property missing");
    }

    private static Map<String, Object> headerPropertyOrNull(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = mapping(value);
            if (Collections.<Object>singletonList("HeaderManager.headers").equals(map.get("property"))) {
                return map;
            }
            for (Object child : map.values()) {
                Map<String, Object> found = headerPropertyOrNull(child);
                if (found != null) return found;
            }
        } else if (value instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) value) {
                Map<String, Object> found = headerPropertyOrNull(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertReceipt(CliTestResult result, int appliedCount) {
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(mapping(new Yaml().load(result.stdout())))
                .containsEntry("appliedCount", Integer.valueOf(appliedCount))
                .containsKeys("createdRefs", "deletedRefs");
    }

    private static String append(String ref, String name, String value) {
        return "  - append:\n      ref: " + ref + "\n"
                + "      property: [HeaderManager.headers]\n"
                + "      row: {Header.name: '" + name + "', Header.value: '" + value + "'}\n";
    }

    private static String insert(String ref, int index, String name, String value) {
        return "  - insert:\n      ref: " + ref + "\n"
                + "      property: [HeaderManager.headers]\n"
                + "      index: " + index + "\n"
                + "      row: {Header.name: '" + name + "', Header.value: '" + value + "'}\n";
    }

    private static String remove(String ref, int index) {
        return "  - remove:\n      ref: " + ref + "\n"
                + "      property: [HeaderManager.headers]\n"
                + "      index: " + index + "\n";
    }
}
