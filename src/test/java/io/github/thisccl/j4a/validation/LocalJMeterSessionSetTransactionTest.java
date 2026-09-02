package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class LocalJMeterSessionSetTransactionTest {
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();

    @TempDir
    Path tempDir;

    @Test
    void staleSourceInvalidatesOnlySourceAndPreservesTarget() throws Exception {
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 100);
        LocalJMeterWorkerOperations operations = operations(registry);
        Path source = sourceCopy("fingerprint-source.jmx");
        Path target = sourceCopy("fingerprint-target.jmx");
        Path companion = sourceCopy("fingerprint-companion.jmx");
        String sourceRef = lastReference(operations.execute(read(source, null)));
        String targetRef = firstReference(operations.execute(read(target, null)));
        String companionRef = firstReference(operations.execute(read(companion, null)));
        byte[] targetBefore = Files.readAllBytes(target);
        Files.write(source, "external-change".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> LocalJMeterWorkerMutations.setProperty(
                set(source, target, sourceRef), registry))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertUnavailable(operations, source, sourceRef);
        assertFocused(operations, target, targetRef);
        assertFocused(operations, companion, companionRef);
    }

    @Test
    void invalidPropertyKeepsMappingsAndTargetBytesUnchanged() throws Exception {
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 100);
        LocalJMeterWorkerOperations operations = operations(registry);
        Path source = sourceCopy("invalid-source.jmx");
        Path target = sourceCopy("invalid-target.jmx");
        String sourceRef = lastReference(operations.execute(read(source, null)));
        byte[] targetBefore = Files.readAllBytes(target);

        LocalJMeterWorkerRequest invalid = LocalJMeterWorkerRequest.setProperty(
                source, JMETER_HOME, target, sourceRef,
                "missing.property", "value", "STRING");
        assertThatThrownBy(() -> LocalJMeterWorkerMutations.setProperty(invalid, registry))
                .isInstanceOf(Exception.class);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertFocused(operations, source, sourceRef);
    }

    @Test
    void filesystemCommitFailureKeepsMappingsAndTargetDirectoryUnchanged() throws Exception {
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 100);
        LocalJMeterWorkerOperations operations = operations(registry);
        Path source = sourceCopy("write-source.jmx");
        Path target = Files.createDirectory(tempDir.resolve("write-target"));
        Path owned = target.resolve("owned");
        Files.write(owned, "sentinel".getBytes(StandardCharsets.UTF_8));
        String sourceRef = lastReference(operations.execute(read(source, null)));

        assertThatThrownBy(() -> LocalJMeterWorkerMutations.setProperty(
                set(source, target, sourceRef), registry))
                .isInstanceOf(LocalJMeterFileCommitter.CommitException.class);
        assertThat(owned).hasContent("sentinel");
        assertFocused(operations, source, sourceRef);
    }

    private static LocalJMeterWorkerOperations operations(SessionReferenceRegistry registry) {
        return LocalJMeterWorkerOperations.session(
                registry, new LocalJMeterWorkerOrderCapability(() -> { }));
    }

    private Path sourceCopy(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), path);
        return path;
    }

    private static LocalJMeterWorkerRequest read(Path source, String reference) {
        return LocalJMeterWorkerRequest.renderReadData(
                source, JMETER_HOME, "5", reference, "NONE", "false");
    }

    private static LocalJMeterWorkerRequest set(Path source, Path target, String reference) {
        return LocalJMeterWorkerRequest.setProperty(
                source, JMETER_HOME, target, reference,
                "[HTTPSampler.domain]", "transaction.example", "STRING");
    }

    private static void assertFocused(
            LocalJMeterWorkerOperations operations, Path source, String reference) throws Exception {
        assertThat(references(operations.execute(read(source, reference)))).contains(reference);
    }

    private static void assertUnavailable(
            LocalJMeterWorkerOperations operations, Path source, String reference) {
        assertThatThrownBy(() -> operations.execute(read(source, reference)))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
    }

    private static String firstReference(String rendered) {
        return references(rendered).get(0);
    }

    private static String lastReference(String rendered) {
        List<String> references = references(rendered);
        return references.get(references.size() - 1);
    }

    private static List<String> references(String rendered) {
        List<String> references = new ArrayList<String>();
        collectReferences(new Yaml().load(rendered), references);
        assertThat(references).isNotEmpty();
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
}
