package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.addPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.addLoopControllerPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.addNestedHttpPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.defaultsOnlyHttpAddPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.defaultsOnlyHttpAddPatchWithEmptyProperties;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.THREAD_GROUP_REF;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.deletePatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.elementNames;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.moveBeforePatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.overlayHttpAddPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.propertyAsString;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.refFor;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.setPatch;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMainWithLocalRuntime;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class MainApplyMutationTest {
    @TempDir
    Path tempDir;

    @Test
    void applySetWritesValidatedOutputAndPreservesInput() throws IOException {
        Path input = fixture("simple-http.jmx");
        Path output = tempDir.resolve("set-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(setPatch("api.example.test"),
                "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains("appliedCount: 1");
        assertThat(propertyAsString(output, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo("api.example.test");
        assertThat(propertyAsString(input, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo("example.test");
    }

    @Test
    void applyAddInsertsSupportedComponentUnderParent() throws IOException {
        Path output = tempDir.resolve("add-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(addPatch("GET /health"),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains("appliedCount: 1");
        assertThat(elementNames(output)).containsSubsequence("Synthetic HTTP Request", "GET /health");
        assertThat(propertyAsString(output, "GET /health", "HTTPSampler.domain")).isEqualTo("api.example.test");
        assertThat(propertyAsString(output, "GET /health", "HTTPSampler.path")).isEqualTo("/health");
    }

    @Test
    void applyCommitPublishesReloadedTargetRefForSurvivingAlias() throws IOException {
        Path output = tempDir.resolve("aliased-add-output.jmx");
        String patch = defaultsOnlyHttpAddPatch().replace(
                "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n",
                "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                        + "      as: created_http\n");

        CliTestResult result = runMainWithLocalRuntime(patch,
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> receipt = (java.util.Map<String, Object>) new Yaml().load(result.stdout());
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> created =
                (java.util.List<java.util.Map<String, Object>>) receipt.get("createdRefs");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> changes =
                (java.util.List<java.util.Map<String, Object>>) receipt.get("changeResults");
        assertThat(created).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("alias", "created_http"));
        assertThat(changes).singleElement().satisfies(change -> assertThat(change)
                .containsEntry("status", "committed")
                .containsEntry("resultRef", created.get(0).get("ref")));
        assertThat(refFor(output, "HTTP Request")).isEqualTo(created.get(0).get("ref"));
    }

    @Test
    void applyCommitProvesGenericTestBeanRefAgainstItsActualReloadedClass() throws IOException {
        Path output = tempDir.resolve("csv-alias-output.jmx");
        String patch = "changes:\n"
                + "  - add:\n"
                + "      parent: " + THREAD_GROUP_REF + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.config.CSVDataSet\n"
                + "      as: csv\n";

        CliTestResult result = runMainWithLocalRuntime(patch,
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> receipt = (java.util.Map<String, Object>) new Yaml().load(result.stdout());
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> created =
                (java.util.List<java.util.Map<String, Object>>) receipt.get("createdRefs");
        String ref = (String) created.get(0).get("ref");
        CliTestResult read = runMainWithLocalRuntime("", "read", output.toString(), "--ref", ref);
        assertThat(read.exitCode()).as(read.stderr()).isZero();
        assertThat(read.stdout())
                .contains("component: org.apache.jmeter.config.CSVDataSet")
                .doesNotContain("component: org.apache.jmeter.testbeans.gui.TestBeanGUI");
    }

    @Test
    void applyInPlaceDeletedRefsFollowInputTreeOrderNotCardOrder() throws IOException {
        Path input = tempDir.resolve("reverse-delete-input.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        assertThat(runMainWithLocalRuntime(addPatch("Second HTTP Request"),
                "apply", input.toString(), "--patch", "-", "--override").exitCode()).isZero();
        String originalRef = refFor(input, "Synthetic HTTP Request");
        String secondRef = refFor(input, "Second HTTP Request");
        String patch = "changes:\n"
                + "  - delete:\n      ref: " + secondRef + "\n"
                + "  - delete:\n      ref: " + originalRef + "\n";

        CliTestResult result = runMainWithLocalRuntime(patch,
                "apply", input.toString(), "--patch", "-", "--override");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> receipt = (java.util.Map<String, Object>) new Yaml().load(result.stdout());
        assertThat((java.util.List<?>) receipt.get("deletedRefs"))
                .containsExactly(originalRef, secondRef);
    }

    @Test
    void applyAddMapsUnregisteredRawElementClassToIdentityNotFoundWithoutWritingTarget() throws IOException {
        Path input = fixture("simple-http.jmx");
        Path output = tempDir.resolve("identity-not-found-output.jmx");
        String patch = "changes:\n"
                + "  - add:\n"
                + "      parent: " + THREAD_GROUP_REF + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.threads.ThreadGroup\n";

        CliTestResult result = runMainWithLocalRuntime(patch,
                "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Error code: COMPONENT_IDENTITY_NOT_FOUND")
                .contains("Failure class: semantic", "Phase: materialization",
                        "Change index: 0", "Operation: add",
                        "component", "parent", "position", "properties")
                .doesNotContain("changeResults", "value:")
                .doesNotContain("INVALID_PLACEMENT", "JMETER_PLACEMENT_REJECTED");
        assertThat(output).doesNotExist();
    }

    @Test
    void applyAddMaterializesDefaultsWhenPropertiesAreOmitted() throws IOException {
        Path output = tempDir.resolve("defaults-only-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(defaultsOnlyHttpAddPatch(),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains("appliedCount: 1");
        assertThat(elementNames(output)).containsSubsequence(
                "Synthetic HTTP Request", "HTTP Request");
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).contains(
                "guiclass=\"HttpTestSampleGui\"", "testclass=\"HTTPSamplerProxy\"",
                "name=\"HTTPSampler.method\">GET");
    }

    @Test
    void applyAddTreatsEmptyPropertiesAsDefaultsOnly() throws IOException {
        Path output = tempDir.resolve("empty-properties-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(defaultsOnlyHttpAddPatchWithEmptyProperties(),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(elementNames(output)).contains("HTTP Request");
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).contains(
                "guiclass=\"HttpTestSampleGui\"", "testclass=\"HTTPSamplerProxy\"",
                "name=\"HTTPSampler.method\">GET");
    }

    @Test
    void applyAddOverlaysPropertiesAfterMaterializingDefaults() throws IOException {
        Path output = tempDir.resolve("overlay-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(overlayHttpAddPatch("GET /phase4", "/phase4"),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains("appliedCount: 1");
        assertThat(propertyAsString(output, "GET /phase4", "HTTPSampler.path")).isEqualTo("/phase4");
        assertThat(propertyAsString(output, "GET /phase4", "HTTPSampler.method")).isEqualTo("GET");
    }

    @Test
    void applyAddSupportsCatalogLoopControllerAndNestedSampler() throws IOException {
        Path output = tempDir.resolve("loop-output.jmx");

        CliTestResult loopResult = runMainWithLocalRuntime(addLoopControllerPatch(),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());
        assertThat(loopResult.exitCode()).as(loopResult.stderr()).isZero();
        assertThat(loopResult.stderr()).isEmpty();
        String loopRef = refFor(output, "Loop Controller");

        Path nestedOutput = tempDir.resolve("nested-loop-output.jmx");
        CliTestResult nestedResult = runMainWithLocalRuntime(addNestedHttpPatch(loopRef),
                "apply", output.toString(), "--patch", "-", "--out", nestedOutput.toString());

        assertThat(nestedResult.exitCode()).as(nestedResult.stderr()).isZero();
        assertThat(nestedResult.stderr()).isEmpty();
        assertThat(elementNames(nestedOutput)).containsSubsequence("Loop Controller", "GET /looped");
        assertThat(propertyAsString(nestedOutput, "GET /looped", "HTTPSampler.path")).isEqualTo("/looped");
    }

    @Test
    void applyMoveReordersExistingComponentWithinParent() throws IOException {
        Path input = tempDir.resolve("move-input.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        CliTestResult addResult = runMainWithLocalRuntime(
                addPatch("GET /health"), "apply", input.toString(), "--patch", "-", "--override");
        assertThat(addResult.exitCode()).isZero();

        Path output = tempDir.resolve("move-output.jmx");
        CliTestResult result = runMainWithLocalRuntime(moveBeforePatch(refFor(input, "GET /health")),
                "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(elementNames(output)).containsSubsequence("GET /health", "Synthetic HTTP Request");
    }

    @Test
    void applyDeleteRemovesExistingComponent() throws IOException {
        Path output = tempDir.resolve("delete-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(deletePatch(),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(elementNames(output)).doesNotContain("Synthetic HTTP Request");
        assertThat(elementNames(fixture("simple-http.jmx"))).contains("Synthetic HTTP Request");
    }

    @Test
    void applyMoveAndDeleteResolveRefsAgainstOriginalInput() throws IOException {
        Path input = tempDir.resolve("stale-ref-input.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        CliTestResult addResult = runMainWithLocalRuntime(
                addPatch("GET /health"), "apply", input.toString(), "--patch", "-", "--override");
        assertThat(addResult.exitCode()).isZero();
        CliTestResult secondAddResult = runMainWithLocalRuntime(
                addPatch("delete-me"), "apply", input.toString(), "--patch", "-", "--override");
        assertThat(secondAddResult.exitCode()).isZero();

        Path output = tempDir.resolve("stale-ref-output.jmx");
        String patch = String.format("changes:\n  - move:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                        + "      parent: %s\n      before: %s\n  - delete:\n      ref: %s\n"
                        + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n",
                refFor(input, "GET /health"), THREAD_GROUP_REF,
                refFor(input, "Synthetic HTTP Request"), refFor(input, "delete-me"));
        CliTestResult result = runMainWithLocalRuntime(patch,
                "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(elementNames(output)).contains("GET /health");
        assertThat(elementNames(output)).contains("Synthetic HTTP Request");
        assertThat(elementNames(output)).doesNotContain("delete-me");
    }

    @Test
    void applyRejectsMutatedMovePlacementAnchorBeforeWritingForBothSelectorsAndOrders() throws IOException {
        Path input = tempDir.resolve("move-anchor-input.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        assertThat(runMainWithLocalRuntime(addPatch("anchor"),
                "apply", input.toString(), "--patch", "-", "--override").exitCode()).isZero();
        String movingRef = refFor(input, "Synthetic HTTP Request");
        String anchorRef = refFor(input, "anchor");

        for (String selector : new String[] {"before", "after"}) {
            for (String mutationKind : new String[] {"delete", "move"}) {
                for (boolean mutationFirst : new boolean[] {false, true}) {
                    Path output = tempDir.resolve(selector + "-" + mutationKind + "-" + mutationFirst + ".jmx");
                    byte[] sentinel = "unchanged".getBytes(StandardCharsets.UTF_8);
                    Files.write(output, sentinel);
                    String placementMove = String.format("  - move:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                            + "      parent: %s\n      %s: %s\n", movingRef, THREAD_GROUP_REF, selector, anchorRef);
                    String anchorMutation = "delete".equals(mutationKind)
                            ? String.format("  - delete:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n", anchorRef)
                            : String.format("  - move:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                                    + "      parent: %s\n      position: last\n", anchorRef, THREAD_GROUP_REF);
                    String patch = "changes:\n" + (mutationFirst
                            ? anchorMutation + placementMove : placementMove + anchorMutation);

                    CliTestResult result = runMainWithLocalRuntime(patch, "apply", input.toString(),
                            "--patch", "-", "--dry-run", "--out", output.toString());

                    assertThat(result.exitCode()).as(result.stderr()).isEqualTo(3);
                    assertThat(result.stdout()).isEmpty();
                    assertThat(result.stderr()).contains("Error code: INVALID_PLACEMENT",
                            "Placement anchor cannot be moved or deleted");
                    assertThat(Files.readAllBytes(output)).isEqualTo(sentinel);
                }
            }
        }
    }

    @Test
    void applySameClassInsertionBeforeChangesTheOriginalNodesFreshRef() throws IOException {
        Path input = fixture("simple-http.jmx");
        String originalRef = refFor(input, "Synthetic HTTP Request");
        Path output = tempDir.resolve("same-class-before-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(addBeforePatch(
                        THREAD_GROUP_REF, originalRef, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui", "Inserted HTTP Request"),
                "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(refFor(output, "Synthetic HTTP Request")).isNotEqualTo(originalRef);
    }

    @Test
    void applyDifferentClassInsertionDoesNotChangeTheOriginalNodesClassOrdinalRef() throws IOException {
        Path input = fixture("simple-http.jmx");
        String originalRef = refFor(input, "Synthetic HTTP Request");
        Path output = tempDir.resolve("different-class-before-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(addBeforePatch(
                        THREAD_GROUP_REF, originalRef, "org.apache.jmeter.control.gui.LoopControlPanel", "Inserted Loop Controller"),
                "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(refFor(output, "Synthetic HTTP Request")).isEqualTo(originalRef);
    }

    @Test
    void applyResolvesEveryOriginalRefBeforeMutatingTheInputSnapshot() throws IOException {
        Path input = fixture("simple-http.jmx");
        String originalRef = refFor(input, "Synthetic HTTP Request");
        Path output = tempDir.resolve("snapshot-binding-output.jmx");
        String patch = String.format(
                "changes:\n"
                        + "  - add:\n"
                        + "      parent: %s\n"
                        + "      before: %s\n"
                        + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                        + "      properties:\n"
                        + "        - property: [TestElement.name]\n"
                        + "          value: Inserted HTTP Request\n"
                        + "          type: string\n"
                        + "  - set:\n"
                        + "      ref: %s\n"
                        + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                        + "      properties:\n"
                        + "        - property: [HTTPSampler.domain]\n"
                        + "          value: original.example.test\n"
                        + "          type: string\n",
                THREAD_GROUP_REF, originalRef, originalRef);

        CliTestResult result = runMainWithLocalRuntime(
                patch, "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(propertyAsString(output, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo("original.example.test");
        assertThat(propertyAsString(output, "Inserted HTTP Request", "HTTPSampler.domain")).isNull();
    }

    @Test
    void applyCannotTargetANodeAddedEarlierInTheSamePatch() throws IOException {
        Path input = fixture("simple-http.jmx");
        Path prepared = tempDir.resolve("prepared-added-node.jmx");
        CliTestResult prepareResult = runMainWithLocalRuntime(addPatch("Added HTTP Request"),
                "apply", input.toString(), "--patch", "-", "--out", prepared.toString());
        assertThat(prepareResult.exitCode()).as(prepareResult.stderr()).isZero();
        String futureRef = refFor(prepared, "Added HTTP Request");
        Path output = tempDir.resolve("must-not-exist.jmx");
        String patch = addPatch("Added HTTP Request")
                + String.format(
                        "  - set:\n"
                                + "      ref: %s\n"
                                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                                + "      properties:\n"
                                + "        - property: [HTTPSampler.domain]\n"
                                + "          value: forbidden.example.test\n"
                                + "          type: string\n",
                        futureRef);

        CliTestResult result = runMainWithLocalRuntime(
                patch, "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("LOCATOR_NOT_FOUND", "read");
        assertThat(output).doesNotExist();
    }

    private static String addBeforePatch(String parentRef, String beforeRef, String component, String name) {
        return String.format(
                "changes:\n"
                        + "  - add:\n"
                        + "      parent: %s\n"
                        + "      before: %s\n"
                        + "      component: %s\n"
                        + "      properties:\n"
                        + "        - property: [TestElement.name]\n"
                        + "          value: %s\n"
                        + "          type: string\n",
                parentRef, beforeRef, component, name);
    }
}
