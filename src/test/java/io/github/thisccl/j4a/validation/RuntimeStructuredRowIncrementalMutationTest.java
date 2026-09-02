package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.apply.MutationChangeResult;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class RuntimeStructuredRowIncrementalMutationTest extends SessionApplyCommitTestSupport {
    @TempDir
    Path tempDir;

    @Test
    void appliesAppendInsertAndRemoveInDeclarationOrderAndReloadsDuplicates() throws Exception {
        Path source = sourceCopy("ordered-source.jmx");
        Path target = tempDir.resolve("ordered-target.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String headerRef = referenceNamed(
                operations(registry).execute(read(source, null)), "Contract Headers");
        String yaml = "changes:\n"
                + append(headerRef, "duplicate", "one")
                + append(headerRef, "duplicate", "one")
                + insert(headerRef, 0, "first", "zero")
                + insert(headerRef, 3, "last", "three")
                + remove(headerRef, 1);

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), patch(yaml), target, false, registry));

        HeaderManager headers = headers(LocalJMeterWorkerJmx.load(target, jmeterHome()));
        assertThat(result.receipt().appliedCount()).isEqualTo(5);
        assertThat(result.receipt().changeResults())
                .extracting(MutationChangeResult::operation)
                .containsExactly("append", "append", "insert", "insert", "remove");
        assertThat(result.receipt().changeResults().get(0).context().toMap())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("ref", headerRef),
                        org.assertj.core.data.MapEntry.entry("property",
                                java.util.Collections.<Object>singletonList("HeaderManager.headers")));
        assertThat(result.receipt().changeResults().get(2).context().toMap())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("ref", headerRef),
                        org.assertj.core.data.MapEntry.entry("property",
                                java.util.Collections.<Object>singletonList("HeaderManager.headers")),
                        org.assertj.core.data.MapEntry.entry("index", Integer.valueOf(0)));
        assertThat(result.receipt().changeResults().get(4).context().toMap())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("ref", headerRef),
                        org.assertj.core.data.MapEntry.entry("property",
                                java.util.Collections.<Object>singletonList("HeaderManager.headers")),
                        org.assertj.core.data.MapEntry.entry("index", Integer.valueOf(1)));
        assertThat(headers.size()).isEqualTo(3);
        assertThat(headerNames(headers)).containsExactly("first", "duplicate", "last");
        assertThat(headerValues(headers)).containsExactly("zero", "one", "three");
    }

    @Test
    void acceptsEveryInsertAndRemoveBoundary() throws Exception {
        Path source = sourceCopy("boundaries-source.jmx");
        Path target = tempDir.resolve("boundaries-target.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String headerRef = referenceNamed(
                operations(registry).execute(read(source, null)), "Contract Headers");
        String yaml = "changes:\n"
                + insert(headerRef, 0, "first", "zero")
                + insert(headerRef, 1, "last", "one")
                + remove(headerRef, 1)
                + remove(headerRef, 0);

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), patch(yaml), target, false, registry));

        assertThat(result.receipt().appliedCount()).isEqualTo(4);
        assertThat(headers(LocalJMeterWorkerJmx.load(target, jmeterHome())).size()).isZero();
    }

    @Test
    void dryRunValidatesOrderedCandidateWithoutPublishingFilesOrReferences() throws Exception {
        Path source = sourceCopy("dry-run-source.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String headerRef = referenceNamed(
                operations(registry).execute(read(source, null)), "Contract Headers");
        Object registryBefore = registrySnapshot(registry);
        Path candidateDirectory = Files.createDirectory(tempDir.resolve("dry-run-candidates"));
        String yaml = "changes:\n"
                + append(headerRef, "later", "one")
                + insert(headerRef, 0, "first", "zero")
                + remove(headerRef, 1);

        MutationResult result = execute(MutationRequest.dryRun(
                source, jmeterHome(), patch(yaml), candidateDirectory, registry));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.DRY_RUN_VALIDATED);
        assertThat(result.receipt().appliedCount()).isEqualTo(3);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidateFiles(candidateDirectory)).isEmpty();
    }

    @Test
    void laterInvalidInsertRollsBackEarlierAppendAndPublishesNoReceipt() throws Exception {
        Path source = sourceCopy("invalid-insert-source.jmx");
        Path target = sourceCopy("invalid-insert-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] targetBefore = Files.readAllBytes(target);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String headerRef = referenceNamed(
                operations(registry).execute(read(source, null)), "Contract Headers");
        Object registryBefore = registrySnapshot(registry);
        String yaml = "changes:\n"
                + append(headerRef, "must-not-commit", "one")
                + insert(headerRef, 2, "out-of-bounds", "two");

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(yaml), target, true, registry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insert index 2")
                .hasMessageContaining("0 to 1");
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    @Test
    void removeFromEmptyCollectionFailsWithoutMutationOrReceipt() throws Exception {
        Path source = sourceCopy("invalid-remove-source.jmx");
        Path target = tempDir.resolve("invalid-remove-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String headerRef = referenceNamed(
                operations(registry).execute(read(source, null)), "Contract Headers");
        Object registryBefore = registrySnapshot(registry);

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch("changes:\n" + remove(headerRef, 0)),
                target, false, registry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Remove index 0")
                .hasMessageContaining("existing row");
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(target).doesNotExist();
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    @Test
    void appendCanTargetAnAliasDeclaredByAnEarlierAdd() throws Exception {
        Path source = sourceCopy("alias-source.jmx");
        Path target = tempDir.resolve("alias-target.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String rootRef = referenceNamed(
                operations(registry).execute(read(source, null)),
                "Structured Collection CLI Contract");
        String yaml = "changes:\n"
                + "  - add:\n"
                + "      parent: " + rootRef + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.gui.HeaderPanel\n"
                + "      as: added_headers\n"
                + append("$added_headers", "alias-row", "value");

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), patch(yaml), target, false, registry));

        List<HeaderManager> managers = LocalJMeterWorkerJmx.load(target, jmeterHome())
                .depthFirstTestElements().stream()
                .filter(HeaderManager.class::isInstance)
                .map(HeaderManager.class::cast)
                .collect(java.util.stream.Collectors.toList());
        assertThat(result.receipt().appliedCount()).isEqualTo(2);
        assertThat(result.publishedReferences()).isPresent();
        assertThat(result.publishedReferences().get().created())
                .extracting(MutationResult.CreatedReference::alias)
                .containsExactly("added_headers");
        assertThat(managers).anySatisfy(manager -> {
            assertThat(manager.size()).isEqualTo(1);
            assertThat(manager.getHeader(0).getName()).isEqualTo("alias-row");
        });
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

    private Path sourceCopy(String name) throws Exception {
        Path source = tempDir.resolve(name);
        Files.copy(java.nio.file.Paths.get(
                "src/test/resources/fixtures/structured-collection-adapters/local-profile-input.jmx"), source);
        return source;
    }

    private static MutationResult execute(MutationRequest request) throws Exception {
        return new LocalMutationTransaction().execute(request);
    }

    private static ApplyPatch patch(String yaml) throws Exception {
        return new ApplyPatchParser().parse(yaml);
    }

    private static HeaderManager headers(JmxTestPlan plan) {
        return plan.depthFirstTestElements().stream()
                .filter(HeaderManager.class::isInstance)
                .map(HeaderManager.class::cast)
                .filter(candidate -> "Contract Headers".equals(candidate.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("header manager missing"));
    }

    private static List<String> headerNames(HeaderManager headers) {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (int index = 0; index < headers.size(); index++) {
            values.add(headers.getHeader(index).getName());
        }
        return values;
    }

    private static List<String> headerValues(HeaderManager headers) {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (int index = 0; index < headers.size(); index++) {
            values.add(headers.getHeader(index).getValue());
        }
        return values;
    }

    private static String referenceNamed(String rendered, String name) {
        Object document = new Yaml().load(rendered);
        String reference = referenceNamed(document, name);
        if (reference == null) {
            throw new AssertionError("reference missing for " + name);
        }
        return reference;
    }

    private static String referenceNamed(Object value, String name) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (name.equals(map.get("name")) && map.get("ref") instanceof String) {
                return (String) map.get("ref");
            }
            for (Object child : map.values()) {
                String reference = referenceNamed(child, name);
                if (reference != null) return reference;
            }
        } else if (value instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) value) {
                String reference = referenceNamed(child, name);
                if (reference != null) return reference;
            }
        }
        return null;
    }

    private static List<Path> candidateFiles(Path directory) throws Exception {
        java.util.ArrayList<Path> candidates = new java.util.ArrayList<Path>();
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(
                directory, "jmx-agent-worker-candidate-*.jmx")) {
            for (Path entry : entries) candidates.add(entry);
        }
        return Collections.unmodifiableList(candidates);
    }
}
