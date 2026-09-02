package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.apply.MutationChangeResult;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMutationTransactionAtomicityTest extends SessionApplyCommitTestSupport {
    @TempDir
    Path tempDir;

    @Test
    void commitsVerifiedMixedPatchBeforePublishingInPlaceReferences() throws Exception {
        Path source = sourceCopy("mixed-in-place.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> oldRefs = references(operations(registry).execute(read(source, null)));
        LocalJMeterWorkerComponents.resetWorkerGenerationForTests();

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), patch(mixedPatch(oldRefs)), source, true, registry));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        assertThat(result.referenceScope()).isEqualTo(MutationResult.ReferenceScope.SESSION);
        assertThat(LocalJMeterGuiSemanticInstrumentation.snapshot().observationAttempts()).isZero();
        assertThat(result.receipt().appliedCount()).isEqualTo(8);
        assertThat(result.receipt().changeResults())
                .extracting(MutationChangeResult::index)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(result.receipt().changeResults())
                .extracting(MutationChangeResult::operation)
                .containsExactly("set", "add", "move", "add", "move", "add", "add", "delete");
        assertThat(result.receipt().changeResults())
                .extracting(MutationChangeResult::status)
                .containsOnly(MutationChangeResult.Status.COMMITTED);
        assertThat(result.publishedReferences()).isPresent();
        assertThat(result.publishedReferences().get().created())
                .extracting(MutationResult.CreatedReference::alias)
                .containsExactly("controller", "child");
        assertThat(result.receipt().changeResults().get(1).resultRef())
                .contains(result.publishedReferences().get().created().get(0).publicReference());
        assertThat(result.receipt().changeResults().get(3).resultRef())
                .contains(result.publishedReferences().get().created().get(1).publicReference());
        assertThat(result.receipt().changeResults().get(5).resultRef()).isEmpty();
        assertThat(result.receipt().changeResults().get(6).resultRef()).isEmpty();
        assertThat(result.receipt().changeResults().get(0).context().toMap())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("ref", oldRefs.get(0)),
                        org.assertj.core.data.MapEntry.entry("properties",
                                java.util.Collections.singletonList(
                                        java.util.Collections.<Object>singletonList("TestElement.name"))));
        assertThat(result.receipt().changeResults().get(1).context().toMap())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("alias", "controller"),
                        org.assertj.core.data.MapEntry.entry(
                                "component", "org.apache.jmeter.control.gui.LogicControllerGui"),
                        org.assertj.core.data.MapEntry.entry("parent", oldRefs.get(1)),
                        org.assertj.core.data.MapEntry.entry("position", "last"),
                        org.assertj.core.data.MapEntry.entry("properties", java.util.Collections.emptyList()));
        JmxTestPlan reloaded = LocalJMeterWorkerJmx.load(source, jmeterHome());
        assertThat(reloaded.depthFirstTestElements())
                .filteredOn(GenericController.class::isInstance).hasSize(1);
        assertThat(reloaded.depthFirstTestElements())
                .filteredOn(HTTPSamplerProxy.class::isInstance).hasSize(3);
        assertThat(registry.documentStatus(
                DocumentIdentity.of(jmeterHome(), source), fingerprint(source)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        for (MutationResult.CreatedReference created
                : result.publishedReferences().get().created()) {
            assertThat(references(operations(registry).execute(
                    read(source, created.publicReference()))))
                    .contains(created.publicReference());
        }
    }

    @Test
    void dryRunValidatesThenDiscardsCandidateAndPreparedReferences() throws Exception {
        Path source = sourceCopy("dry-run.jmx");
        byte[] before = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        Object registryBefore = registrySnapshot(registry);
        Path candidateDirectory = Files.createDirectory(tempDir.resolve("dry-candidates"));

        MutationResult result = execute(MutationRequest.dryRun(
                source, jmeterHome(), patch(mixedPatch(refs)), candidateDirectory, registry));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.DRY_RUN_VALIDATED);
        assertThat(result.referenceScope()).isEqualTo(MutationResult.ReferenceScope.NONE);
        assertThat(result.receipt().appliedCount()).isEqualTo(8);
        assertThat(result.receipt().changeResults())
                .extracting(MutationChangeResult::status)
                .containsOnly(MutationChangeResult.Status.VALIDATED);
        assertThat(result.receipt().changeResults())
                .allSatisfy(change -> assertThat(change.resultRef()).isEmpty());
        assertThat(result.publishedReferences()).isEmpty();
        assertThat(Files.readAllBytes(source)).containsExactly(before);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidateFiles(candidateDirectory)).isEmpty();
    }

    @Test
    void malformedLaterOperationPreservesFilesRegistryAndCandidates() throws Exception {
        Path source = sourceCopy("malformed-later-source.jmx");
        Path target = sourceCopy("malformed-later-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] targetBefore = Files.readAllBytes(target);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String ref = references(operations(registry).execute(read(source, null))).get(0);
        Object registryBefore = registrySnapshot(registry);
        String yaml = "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: must-not-commit\n          type: string\n"
                + "  - delete:\n      ref: unavailable-ref\n";

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(yaml), target, true, registry)))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    @Test
    void unknownNestedScalarAddressPreservesSourceAndCreatesNoDestination() throws Exception {
        Path source = sourceCopy("unknown-nested-source.jmx");
        Path target = tempDir.resolve("unknown-nested-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String ref = references(operations(registry).execute(read(source, null))).get(0);
        Object registryBefore = registrySnapshot(registry);
        String yaml = "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n"
                + "        - property: [TestElement.name, missing-child]\n"
                + "          value: must-not-commit\n"
                + "          type: string\n";

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(yaml), target, false, registry)))
                .isInstanceOf(io.github.thisccl.j4a.path.PropertyPathResolutionException.class)
                .hasMessageContaining("missing-child");
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(target).doesNotExist();
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    @Test
    void addOverlayResolvesItsScalarAddressAgainstTheMaterializedCandidate() throws Exception {
        Path source = sourceCopy("materialized-overlay-source.jmx");
        Path target = tempDir.resolve("materialized-overlay-target.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        String yaml = "changes:\n"
                + "  - add:\n"
                + "      parent: " + refs.get(1) + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [HTTPSampler.path]\n"
                + "          value: /materialized-address\n"
                + "          type: string\n";

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), patch(yaml), target, false, registry));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        assertThat(LocalJMeterWorkerJmx.load(target, jmeterHome()).depthFirstTestElements())
                .filteredOn(HTTPSamplerProxy.class::isInstance)
                .map(HTTPSamplerProxy.class::cast)
                .anySatisfy(sampler -> assertThat(sampler.getPath())
                        .isEqualTo("/materialized-address"));
    }

    @Test
    void capacityAndFilesystemFailuresDoNotCommitOrPublish() throws Exception {
        Path source = sourceCopy("failure-source.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 3);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        Object registryBefore = registrySnapshot(registry);
        Path capacityTarget = tempDir.resolve("capacity-target.jmx");
        byte[] sentinel = "capacity-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(capacityTarget, sentinel);
        String add = "changes:\n  - add:\n      parent: " + refs.get(1) + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.control.gui.LogicControllerGui\n"
                + "      as: created\n";

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(add), capacityTarget, true, registry)))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_CAPACITY_EXCEEDED"));
        assertThat(Files.readAllBytes(capacityTarget)).containsExactly(sentinel);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);

        SessionReferenceRegistry roomy = new SessionReferenceRegistry(3, 40);
        String sourceRef = references(operations(roomy).execute(read(source, null))).get(0);
        Object roomyBefore = registrySnapshot(roomy);
        Path directoryTarget = Files.createDirectory(tempDir.resolve("directory-target"));
        Files.write(directoryTarget.resolve("owned"), sentinel);
        String set = setName(sourceRef, "filesystem-failure");
        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(set), directoryTarget, true, roomy)))
                .isInstanceOf(LocalJMeterFileCommitter.CommitException.class);
        assertThat(directoryTarget.resolve("owned")).hasBinaryContent(sentinel);
        assertThat(registrySnapshot(roomy)).isEqualTo(roomyBefore);
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    @Test
    void copyPublishesOnlyTargetAliasesAndPreservesSourceIdentity() throws Exception {
        Path source = sourceCopy("copy-source.jmx");
        Path target = sourceCopy("copy-target.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 50);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        String sourceFingerprint = fingerprint(source);
        java.util.Set<String> sourceTokens = registrySnapshotTokens(
                registry, DocumentIdentity.of(jmeterHome(), source));

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), patch(mixedPatch(refs)), target, true, registry));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        assertThat(registry.documentStatus(
                DocumentIdentity.of(jmeterHome(), source), sourceFingerprint))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        assertThat(registrySnapshotTokens(
                registry, DocumentIdentity.of(jmeterHome(), source)))
                .isEqualTo(sourceTokens);
        assertThat(registrySnapshotTokens(
                registry, DocumentIdentity.of(jmeterHome(), target)))
                .containsExactlyInAnyOrderElementsOf(publicReferences(result));
    }

    @Test
    void createNewCopyCleansCandidateAndPublishesOnlyTargetAliases() throws Exception {
        Path source = sourceCopy("create-copy-source.jmx");
        Path target = tempDir.resolve("create-copy-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 50);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        java.util.Set<String> sourceTokens = registrySnapshotTokens(
                registry, DocumentIdentity.of(jmeterHome(), source));

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), patch(mixedPatch(refs)), target, false, registry));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.exists(target)).isTrue();
        assertThat(registrySnapshotTokens(
                registry, DocumentIdentity.of(jmeterHome(), source)))
                .containsExactlyInAnyOrderElementsOf(sourceTokens);
        assertThat(registrySnapshotTokens(
                registry, DocumentIdentity.of(jmeterHome(), target)))
                .containsExactlyInAnyOrderElementsOf(publicReferences(result));
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    @Test
    void createNewCleanupFailurePreservesCommitFailureAndDiscardsPreparedReferences()
            throws Exception {
        Path source = sourceCopy("cleanup-failure-source.jmx");
        Path target = tempDir.resolve("cleanup-failure-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        String ref = references(operations(registry).execute(read(source, null))).get(0);
        Object registryBefore = registrySnapshot(registry);
        SecurityManager originalSecurityManager = System.getSecurityManager();
        Throwable failure;

        try {
            System.setSecurityManager(new JmxDeleteDenyingSecurityManager(target));
            failure = org.assertj.core.api.Assertions.catchThrowable(() -> execute(
                    MutationRequest.commit(source, jmeterHome(), patch(setName(
                            ref, "cleanup-failure")), target, false, registry)));
        } finally {
            System.setSecurityManager(originalSecurityManager);
        }

        assertThat(failure).isInstanceOf(LocalJMeterFileCommitter.CommitException.class);
        LocalJMeterFileCommitter.CommitException commitFailure =
                (LocalJMeterFileCommitter.CommitException) failure;
        assertThat(commitFailure.getCause()).isInstanceOf(java.io.IOException.class);
        assertThat(commitFailure.getCause().getSuppressed()).hasSize(1);
        assertThat(commitFailure.getSuppressed()).hasSize(1);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(target).exists();
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidateFiles(tempDir)).hasSize(1);
    }

    @Test
    void materializesHttpSamplerBeforeNormalizingRuntimeProvenRows() throws Exception {
        Path source = sourceCopy("rows-source.jmx");
        Path target = tempDir.resolve("rows-target.jmx");
        LocalJMeterWorkerRuntime.initialize(jmeterHome());
        ApplyPatch rowsPatch = patch("changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          type: string\n"
                + "          value: Transaction Rows\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: " + HTTPArgument.class.getName() + "\n"
                + "            rows:\n"
                + "              - Argument.name: duplicate\n"
                + "                Argument.value: one\n"
                + "                Argument.metadata: '='\n"
                + "                HTTPArgument.always_encode: true\n"
                + "                HTTPArgument.use_equals: false\n"
                + "                HTTPArgument.content_type: text/plain\n"
                + "              - Argument.name: duplicate\n"
                + "                Argument.value: two\n"
                + "                Argument.metadata: '='\n"
                + "                HTTPArgument.always_encode: false\n"
                + "                HTTPArgument.use_equals: true\n"
                + "                HTTPArgument.content_type: application/json\n");

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), rowsPatch, target, false, null));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        assertThat(result.referenceScope()).isEqualTo(MutationResult.ReferenceScope.TARGET_SNAPSHOT);
        HTTPSamplerProxy sampler = LocalJMeterWorkerJmx.load(target, jmeterHome())
                .depthFirstTestElements().stream()
                .filter(HTTPSamplerProxy.class::isInstance)
                .map(HTTPSamplerProxy.class::cast)
                .filter(candidate -> "Transaction Rows".equals(candidate.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("added sampler missing"));
        Arguments arguments = (Arguments) sampler
                .getProperty("HTTPsampler.Arguments").getObjectValue();
        assertThat(arguments.getArgumentCount()).isEqualTo(2);
        assertThat(arguments.getArgument(0).getClass()).isEqualTo(HTTPArgument.class);
        assertThat(arguments.getArgument(1).getClass()).isEqualTo(HTTPArgument.class);
        assertThat(arguments.getArgument(0).getName()).isEqualTo("duplicate");
        assertThat(arguments.getArgument(0).getValue()).isEqualTo("one");
        assertThat(arguments.getArgument(0).getMetaData()).isEqualTo("=");
        assertThat(arguments.getArgument(0).getDescription()).isEmpty();
        assertThat(arguments.getArgument(1).getValue()).isEqualTo("two");
        assertThat(arguments.getArgument(1).getMetaData()).isEqualTo("=");
        assertThat(arguments.getArgument(1).getDescription()).isEmpty();
        assertThat(((HTTPArgument) arguments.getArgument(0)).isAlwaysEncoded()).isTrue();
        assertThat(arguments.getArgument(0).getPropertyAsBoolean("HTTPArgument.use_equals")).isFalse();
        assertThat(((HTTPArgument) arguments.getArgument(0)).getContentType()).isEqualTo("text/plain");
        assertThat(((HTTPArgument) arguments.getArgument(1)).isAlwaysEncoded()).isFalse();
        assertThat(arguments.getArgument(1).getPropertyAsBoolean("HTTPArgument.use_equals")).isTrue();
        assertThat(((HTTPArgument) arguments.getArgument(1)).getContentType())
                .isEqualTo("application/json");
    }

    @Test
    void appendAndInsertFirstHttpRowsPersistExactConsumerClass() throws Exception {
        Path source = sourceCopy("incremental-http-rows-source.jmx");
        Path target = tempDir.resolve("incremental-http-rows-target.jmx");
        LocalJMeterWorkerRuntime.initialize(jmeterHome());
        ApplyPatch rowsPatch = patch("changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      as: http_rows\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          type: string\n"
                + "          value: Incremental HTTP Rows\n"
                + "  - append:\n"
                + "      ref: $http_rows\n"
                + "      property: [HTTPsampler.Arguments]\n"
                + "      row: {Argument.name: second, Argument.value: two, Argument.metadata: '=', HTTPArgument.always_encode: true, HTTPArgument.use_equals: true, HTTPArgument.content_type: text/plain}\n"
                + "  - insert:\n"
                + "      ref: $http_rows\n"
                + "      property: [HTTPsampler.Arguments]\n"
                + "      index: 0\n"
                + "      row: {Argument.name: first, Argument.value: one, Argument.metadata: '=', HTTPArgument.always_encode: false, HTTPArgument.use_equals: true, HTTPArgument.content_type: application/json}\n");

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), rowsPatch, target, false, null));

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        HTTPSamplerProxy sampler = LocalJMeterWorkerJmx.load(target, jmeterHome())
                .depthFirstTestElements().stream()
                .filter(HTTPSamplerProxy.class::isInstance)
                .map(HTTPSamplerProxy.class::cast)
                .filter(candidate -> "Incremental HTTP Rows".equals(candidate.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("added sampler missing"));
        Arguments arguments = (Arguments) sampler.getProperty("HTTPsampler.Arguments").getObjectValue();
        assertThat(arguments.getArgumentCount()).isEqualTo(2);
        assertThat(arguments.getArgument(0).getClass()).isEqualTo(HTTPArgument.class);
        assertThat(arguments.getArgument(1).getClass()).isEqualTo(HTTPArgument.class);
        assertThat(arguments.getArgument(0).getName()).isEqualTo("first");
        assertThat(arguments.getArgument(0).getValue()).isEqualTo("one");
        assertThat(arguments.getArgument(0).getMetaData()).isEqualTo("=");
        assertThat(arguments.getArgument(0).getDescription()).isEmpty();
        assertThat(arguments.getArgument(1).getName()).isEqualTo("second");
        assertThat(arguments.getArgument(1).getValue()).isEqualTo("two");
        assertThat(arguments.getArgument(1).getMetaData()).isEqualTo("=");
        assertThat(arguments.getArgument(1).getDescription()).isEmpty();
        assertThat(((HTTPArgument) arguments.getArgument(0)).isAlwaysEncoded()).isFalse();
        assertThat(arguments.getArgument(0).getPropertyAsBoolean("HTTPArgument.use_equals")).isTrue();
        assertThat(((HTTPArgument) arguments.getArgument(0)).getContentType())
                .isEqualTo("application/json");
        assertThat(((HTTPArgument) arguments.getArgument(1)).isAlwaysEncoded()).isTrue();
        assertThat(arguments.getArgument(1).getPropertyAsBoolean("HTTPArgument.use_equals")).isTrue();
        assertThat(((HTTPArgument) arguments.getArgument(1)).getContentType()).isEqualTo("text/plain");
    }

    @Test
    void setFirstHttpRowOnLoadedTargetUsesExactGuiConsumer() throws Exception {
        Path source = sourceCopy("set-http-row-source.jmx");
        Path target = tempDir.resolve("set-http-row-target.jmx");
        LocalJMeterWorkerRuntime.initialize(jmeterHome());
        LocalJMeterWorkerComponents.resetWorkerGenerationForTests();
        LocalJMeterWorkerComponents.componentDetails(LocalJMeterWorkerRequest.componentDetails(
                jmeterHome(), "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"));
        LocalJMeterGuiSemanticInstrumentation.Snapshot beforeWrite =
                LocalJMeterGuiSemanticInstrumentation.snapshot();
        ApplyPatch rowsPatch = patch("changes:\n"
                + "  - set:\n"
                + "      ref: jmx_330976848c8e\n"
                + "      properties:\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: " + HTTPArgument.class.getName() + "\n"
                + "            rows:\n"
                + "              - Argument.name: loaded\n"
                + "                Argument.value: target\n"
                + "                Argument.metadata: '='\n"
                + "                HTTPArgument.always_encode: true\n"
                + "                HTTPArgument.use_equals: true\n"
                + "                HTTPArgument.content_type: application/xml\n");

        MutationResult result = execute(MutationRequest.commit(
                source, jmeterHome(), rowsPatch, target, false, null));
        LocalJMeterGuiSemanticInstrumentation.Snapshot afterWrite =
                LocalJMeterGuiSemanticInstrumentation.snapshot();

        assertThat(result.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        assertThat(afterWrite.observationAttempts()).isEqualTo(beforeWrite.observationAttempts());
        assertThat(afterWrite.guiConstructions()).isEqualTo(beforeWrite.guiConstructions());
        assertThat(afterWrite.differentialProbes()).isEqualTo(beforeWrite.differentialProbes());
        HTTPSamplerProxy sampler = LocalJMeterWorkerJmx.load(target, jmeterHome())
                .depthFirstTestElements().stream()
                .filter(HTTPSamplerProxy.class::isInstance)
                .map(HTTPSamplerProxy.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("HTTP sampler missing"));
        Arguments arguments = (Arguments) sampler.getProperty("HTTPsampler.Arguments").getObjectValue();
        assertThat(arguments.getArgumentCount()).isEqualTo(1);
        assertThat(arguments.getArgument(0).getClass()).isEqualTo(HTTPArgument.class);
        assertThat(arguments.getArgument(0).getName()).isEqualTo("loaded");
        assertThat(arguments.getArgument(0).getValue()).isEqualTo("target");
        assertThat(arguments.getArgument(0).getMetaData()).isEqualTo("=");
        assertThat(arguments.getArgument(0).getDescription()).isEmpty();
        assertThat(((HTTPArgument) arguments.getArgument(0)).isAlwaysEncoded()).isTrue();
        assertThat(arguments.getArgument(0).getPropertyAsBoolean("HTTPArgument.use_equals")).isTrue();
        assertThat(((HTTPArgument) arguments.getArgument(0)).getContentType())
                .isEqualTo("application/xml");
    }

    @Test
    void baseArgumentCannotSatisfyEmptyHttpConsumerAndLeaksNoState() throws Exception {
        // Given
        Path source = sourceCopy("invalid-rows-illegal-placement-source.jmx");
        Path target = tempDir.resolve("invalid-rows-illegal-placement-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] targetBefore = "existing-target".getBytes(StandardCharsets.UTF_8);
        Files.write(target, targetBefore);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        Object registryBefore = registrySnapshot(registry);
        LocalJMeterWorkerRuntime.initialize(jmeterHome());
        ApplyPatch patch = patch("changes:\n"
                + "  - add:\n"
                + "      parent: " + refs.get(2) + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      as: must_not_publish\n"
                + "      properties:\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: " + Argument.class.getName() + "\n"
                + "            rows:\n"
                + "              - Argument.name: rejected\n"
                + "                Argument.value: rejected\n"
                + "                Argument.metadata: '='\n");

        // When
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> execute(
                MutationRequest.commit(source, jmeterHome(), patch, target, true, registry)));

        // Then
        assertThat(failure).hasMessageContaining("row_type")
                .hasMessageContaining(HTTPArgument.class.getName())
                .hasMessageNotContaining("JMeter rejected placement");
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(references(operations(registry).execute(read(source, refs.get(0)))))
                .contains(refs.get(0));
        assertThat(LocalJMeterWorkerJmx.load(source, jmeterHome()).depthFirstTestElements())
                .noneMatch(element -> "must_not_publish".equals(element.getName()));
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    @Test
    void illegalPlacementAfterValidPropertiesAttachesAndPublishesNothing() throws Exception {
        // Given
        Path source = sourceCopy("valid-properties-illegal-placement-source.jmx");
        Path target = tempDir.resolve("valid-properties-illegal-placement-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] targetBefore = "existing-placement-target".getBytes(StandardCharsets.UTF_8);
        Files.write(target, targetBefore);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        Object registryBefore = registrySnapshot(registry);
        ApplyPatch patch = patch("changes:\n"
                + "  - add:\n"
                + "      parent: " + refs.get(2) + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      as: must_not_publish\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          type: string\n"
                + "          value: Detached Candidate\n");

        // When
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> execute(
                MutationRequest.commit(source, jmeterHome(), patch, target, true, registry)));

        // Then
        assertThat(failure).isInstanceOf(JMeterPlacementRejectedException.class);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(references(operations(registry).execute(read(source, refs.get(0)))))
                .contains(refs.get(0));
        assertThat(LocalJMeterWorkerJmx.load(source, jmeterHome()).depthFirstTestElements())
                .noneMatch(element -> "Detached Candidate".equals(element.getName()));
        assertThat(candidateFiles(tempDir)).isEmpty();
    }

    private static MutationResult execute(MutationRequest request) throws Exception {
        return new LocalMutationTransaction().execute(request);
    }

    private static ApplyPatch patch(String yaml) throws Exception {
        return new ApplyPatchParser().parse(yaml);
    }

    private static String setName(String ref, String name) {
        return "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: " + name + "\n          type: string\n";
    }

    private Path sourceCopy(String name) throws Exception {
        Path source = tempDir.resolve(name);
        Files.copy(java.nio.file.Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        return source;
    }

    private static List<String> publicReferences(MutationResult result) {
        List<String> values = new java.util.ArrayList<String>();
        for (MutationResult.CreatedReference created
                : result.publishedReferences().get().created()) {
            values.add(created.publicReference());
        }
        return values;
    }

    private static List<Path> candidateFiles(Path directory) throws Exception {
        List<Path> candidates = new java.util.ArrayList<Path>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(
                directory, "jmx-agent-worker-candidate-*.jmx")) {
            for (Path entry : entries) {
                candidates.add(entry);
            }
        }
        return Collections.unmodifiableList(candidates);
    }

    private static final class JmxDeleteDenyingSecurityManager extends SecurityManager {
        private final String target;

        private JmxDeleteDenyingSecurityManager(Path target) {
            this.target = target.toString();
        }

        @Override
        public void checkDelete(String file) {
            String fileName = java.nio.file.Paths.get(file).getFileName().toString();
            if (target.equals(file) || fileName.startsWith("jmx-agent-worker-candidate-")) {
                throw new SecurityException("delete denied for test");
            }
        }

        @Override
        public void checkPermission(Permission permission) {
        }
    }
}
