package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.MutationOutcome;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionApplyCommitTest extends SessionApplyCommitTestSupport {
    @TempDir
    Path tempDir;

    @Test
    void preparesSurvivorsDeletionsAliasesAndReceiptWithoutPublishing() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Path document = tempDir.resolve("prepared.jmx");
        Files.write(document, "before".getBytes(StandardCharsets.UTF_8));
        ProbeElement root = element("root");
        ProbeElement deleted = element("deleted");
        ProbeElement deletedChild = element("deleted-child");
        ProbeElement shifted = element("shifted");
        ListedHashTree tree = new ListedHashTree();
        HashTree children = tree.add(root);
        children.add(deleted).add(deletedChild);
        children.add(shifted);
        JmxTestPlan plan = new JmxTestPlan(tree);
        SourceSnapshot<JmxTestPlan> source = SourceSnapshot.read(document, ignored -> plan);
        DocumentIdentity identity = DocumentIdentity.of(home, document);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 20);

        SessionReferenceRegistry.PreparedState exposure = registry.prepare(identity);
        SessionReferenceSpace exposureSpace = new SessionReferenceSpace(exposure, identity, source);
        BoundReferences initial = exposureSpace.bind(plan);
        String rootRef = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(initial, root);
        String deletedRef = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(initial, deleted);
        String deletedChildRef = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(initial, deletedChild);
        String shiftedRef = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(initial, shifted);
        assertThat(registry.publish(exposureSpace.prepareSuccessfulUse())).isTrue();

        SessionReferenceRegistry.PreparedState mutation = registry.prepare(identity);
        SessionReferenceSpace mutationSpace = new SessionReferenceSpace(mutation, identity, source);
        BoundReferences references = mutationSpace.bind(plan);
        children.remove(deleted);
        ProbeElement created = element("created");
        children.add(created);
        PreparedReferenceState proposal = SessionReferenceSpace.reconcile(references, plan, MutationOutcome.of(
                3,
                Collections.singletonList(MutationOutcome.CreatedNode.of(
                        "surviving", ReferenceTestSupport.handle(created))),
                Collections.emptyList(),
                Collections.emptyMap()));

        SessionApplyReceipt receipt = mutationSpace.prepareApplyCommit(proposal, "after-fingerprint", 3);

        assertThat(receipt.appliedCount()).isEqualTo(3);
        assertThat(receipt.createdRefs()).extracting(SessionApplyReceipt.CreatedRef::alias)
                .containsExactly("surviving");
        assertThat(receipt.createdRefs()).extracting(SessionApplyReceipt.CreatedRef::publicReference)
                .allMatch(value -> value.matches("[A-Za-z0-9_-]{16}"));
        assertThat(receipt.deletedRefs()).containsExactly(deletedRef, deletedChildRef);
        assertThat(registry.documentStatus(identity, source.fingerprint()))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);

        SessionReferenceRegistry.PreparedPublication publication =
                registry.preparePublication(mutationSpace.prepareSuccessfulUse());
        assertThat(registry.documentStatus(identity, source.fingerprint()))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        publication.publish();

        assertThat(registry.documentStatus(identity, "after-fingerprint"))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        assertThat(registrySnapshotTokens(registry, identity))
                .containsExactlyInAnyOrder(rootRef, shiftedRef,
                        receipt.createdRefs().get(0).publicReference())
                .doesNotContainAnyElementsOf(Arrays.asList(deletedRef, deletedChildRef));
    }

    @Test
    void commitsMixedInPlacePatchAndImmediatelyReusesSurvivorAndCreatedRefs() throws Exception {
        Path source = sourceCopy("mixed.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 30);
        LocalJMeterWorkerOperations operations = operations(registry);
        List<String> oldRefs = references(operations.execute(read(source, null)));
        String patch = mixedPatch(oldRefs);

        String payload = operations.execute(LocalJMeterWorkerRequest.applyPatchYaml(
                source, jmeterHome(), patch, source, true));

        Map<String, Object> receipt = mapping(new org.yaml.snakeyaml.Yaml().load(payload));
        assertThat(receipt.get("appliedCount")).isEqualTo(8);
        List<Map<String, Object>> created = mappings(receipt.get("createdRefs"));
        assertThat(created).extracting(row -> row.get("alias"))
                .containsExactly("controller", "child");
        assertThat(receipt.get("deletedRefs")).isEqualTo(Collections.emptyList());
        List<String> createdRefs = new ArrayList<String>();
        for (Map<String, Object> row : created) {
            createdRefs.add((String) row.get("ref"));
        }
        assertThat(createdRefs).allMatch(value -> value.matches("[A-Za-z0-9_-]{16}"));
        assertThat(createdRefs).doesNotContainAnyElementsOf(oldRefs);
        for (String survivor : oldRefs) {
            assertThat(references(operations.execute(read(source, survivor)))).contains(survivor);
        }
        for (String createdRef : createdRefs) {
            assertThat(references(operations.execute(read(source, createdRef)))).contains(createdRef);
        }
        assertThat(registry.documentStatus(
                DocumentIdentity.of(jmeterHome(), source), fingerprint(source)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        assertThat(payload)
                .contains(oldRefs.get(0), oldRefs.get(1), oldRefs.get(2), "temporary")
                .doesNotContain("Mixed Plan");
    }

    @Test
    void externalSourceChangeInvalidatesOnlySourceWithoutCommit() throws Exception {
        Path source = sourceCopy("source-change.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 20);
        LocalJMeterWorkerOperations operations = operations(registry);
        String ref = references(operations.execute(read(source, null))).get(0);
        String patch = "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: candidate-only\n          type: string\n";

        Files.write(source, "external-change".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, jmeterHome(), patch, source, true),
                registry))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(Files.readAllBytes(source)).isNotEqualTo(sourceBefore);
        assertThat(registry.documentStatus(DocumentIdentity.of(jmeterHome(), source), fingerprint(source)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.ABSENT);
    }

    @Test
    void filesystemCommitFailurePreservesExactLiveStateAndTarget() throws Exception {
        Path source = sourceCopy("filesystem-failure.jmx");
        byte[] before = Files.readAllBytes(source);
        Path target = Files.createDirectory(tempDir.resolve("filesystem-target"));
        Path owned = target.resolve("owned");
        Files.write(owned, "sentinel".getBytes(StandardCharsets.UTF_8));
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 20);
        LocalJMeterWorkerOperations operations = operations(registry);
        String ref = references(operations.execute(read(source, null))).get(0);
        Object registryBefore = registrySnapshot(registry);
        String patch = "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: must-not-commit\n          type: string\n";

        assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, jmeterHome(), patch, target, true),
                registry))
                .isInstanceOf(LocalJMeterFileCommitter.CommitException.class);
        assertThat(Files.readAllBytes(source)).containsExactly(before);
        assertThat(owned).hasContent("sentinel");
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
    }

    @Test
    void missingSourceInvalidatesOnlyAffectedSourceWithoutCommitOrPublication() throws Exception {
        Path source = sourceCopy("precommit-read.jmx");
        Path unaffected = sourceCopy("unaffected.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 20);
        LocalJMeterWorkerOperations operations = operations(registry);
        String ref = references(operations.execute(read(source, null))).get(0);
        List<String> unaffectedRefs = references(operations.execute(read(unaffected, null)));
        Object unaffectedBefore = registrySnapshot(registry);
        String patch = "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: must-not-commit\n          type: string\n";

        Files.delete(source);
        assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, jmeterHome(), patch, source, true),
                registry))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(source).doesNotExist();
        assertThat(registry.documentStatus(DocumentIdentity.of(jmeterHome(), unaffected), fingerprint(unaffected)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        assertThat(registrySnapshotTokens(registry, DocumentIdentity.of(jmeterHome(), unaffected)))
                .containsExactlyInAnyOrderElementsOf(unaffectedRefs);
        assertThat(registrySnapshot(registry)).isNotEqualTo(unaffectedBefore);
    }

    private static ProbeElement element(String name) {
        ProbeElement element = new ProbeElement();
        element.setName(name);
        element.setProperty(TestElement.GUI_CLASS, ProbeElement.class.getName());
        element.setProperty(TestElement.TEST_CLASS, ProbeElement.class.getName());
        return element;
    }

    private Path sourceCopy(String name) throws Exception {
        Path source = tempDir.resolve(name);
        Files.copy(java.nio.file.Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        return source;
    }

    private static final class ProbeElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;
    }

}
