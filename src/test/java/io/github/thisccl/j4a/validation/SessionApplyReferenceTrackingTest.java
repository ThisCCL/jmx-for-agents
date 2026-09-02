package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class SessionApplyReferenceTrackingTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsEveryOrdinaryReferenceBeforeTheFirstMutation() throws Exception {
        Path source = sourceCopy("atomic-source.jmx");
        Path output = tempDir.resolve("atomic-output.jmx");
        byte[] sentinel = "unmodified-output".getBytes(StandardCharsets.UTF_8);
        Files.write(output, sentinel);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 100);
        LocalJMeterWorkerOperations operations = operations(registry);
        String valid = references(operations.execute(read(source, null))).get(2);
        String patch = "changes:\n"
                + "  - set:\n"
                + "      ref: " + valid + "\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: must-not-apply\n"
                + "          type: string\n"
                + "  - delete:\n"
                + "      ref: unavailable-ref\n";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, jmeterHome(), patch, output, true), registry))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
        assertThat(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))
                .doesNotContain("must-not-apply");
    }

    @Test
    void retainedProofLossInvalidatesOnlyTheAffectedDocumentBeforeMutation() throws Exception {
        Path source = sourceCopy("proof-source.jmx");
        Path companion = sourceCopy("proof-companion.jmx");
        Path output = tempDir.resolve("proof-output.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] sentinel = "proof-output-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(output, sentinel);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 100);
        LocalJMeterWorkerOperations operations = operations(registry);
        List<String> sourceRefs = references(operations.execute(read(source, null)));
        String companionRef = references(operations.execute(read(companion, null))).get(0);
        corruptFirstRetainedLocator(registry, DocumentIdentity.of(jmeterHome(), source));
        String patch = "changes:\n"
                + "  - set:\n"
                + "      ref: " + sourceRefs.get(sourceRefs.size() - 1) + "\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: must-not-apply\n"
                + "          type: string\n";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, jmeterHome(), patch, output, true), registry))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> operations.execute(read(source, sourceRefs.get(sourceRefs.size() - 1))))
                .isInstanceOf(ReferenceFailure.class);
        assertThat(references(operations.execute(read(companion, companionRef)))).contains(companionRef);
    }

    private Path sourceCopy(String name) throws Exception {
        Path source = tempDir.resolve(name);
        Files.copy(java.nio.file.Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        return source;
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    private static LocalJMeterWorkerOperations operations(SessionReferenceRegistry registry) {
        return LocalJMeterWorkerOperations.session(
                registry, new LocalJMeterWorkerOrderCapability(() -> { }));
    }

    private static LocalJMeterWorkerRequest read(Path source, String reference) {
        return LocalJMeterWorkerRequest.renderReadData(
                source, jmeterHome(), "5", reference, "NONE", "false");
    }

    private static List<String> references(String rendered) {
        List<String> values = new ArrayList<String>();
        collectReferences(new Yaml().load(rendered), values);
        return values;
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

    private static void corruptFirstRetainedLocator(
            SessionReferenceRegistry registry, DocumentIdentity identity) throws Exception {
        Field liveStateField = SessionReferenceRegistry.class.getDeclaredField("liveState");
        liveStateField.setAccessible(true);
        Object state = ((AtomicReference<?>) liveStateField.get(registry)).get();
        Field documentsField = state.getClass().getDeclaredField("documents");
        documentsField.setAccessible(true);
        Object document = ((Map<?, ?>) documentsField.get(state)).get(identity);
        Field recordsField = document.getClass().getDeclaredField("recordsByToken");
        recordsField.setAccessible(true);
        Object record = ((Map<?, ?>) recordsField.get(document)).values().iterator().next();
        Field locatorField = record.getClass().getDeclaredField("locator");
        locatorField.setAccessible(true);
        locatorField.set(record, "jmx_corrupted-retained-locator");
    }

}
