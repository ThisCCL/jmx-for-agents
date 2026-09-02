package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.HTTP_REQUEST_REF;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.assertInvalidAddDoesNotOverwrite;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.defaultsOnlyHttpAddPatchWithoutPlacement;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.elementNames;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.httpAddPatchAtPosition;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.moveAfterItselfPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.moveAfterRefPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.moveBeforeItselfPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.moveBeforeRefPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.moveUnderParentPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.nestedLoopInput;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.threadGroupUnderThreadGroupPatch;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMainWithLocalRuntime;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.cli.ApplyMutationTestSupport.NestedLoopInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainApplyPlacementValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void applyMoveRejectsSelfPlacementBeforeWriting() throws IOException {
        assertInvalidMoveDoesNotOverwrite(moveBeforeItselfPatch(HTTP_REQUEST_REF),
                "INVALID_PLACEMENT", "cannot be placed before itself");
    }

    @Test
    void applyMoveRejectsSelfParentBeforeWriting() throws IOException {
        assertInvalidMoveDoesNotOverwrite(moveUnderParentPatch(HTTP_REQUEST_REF, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui", HTTP_REQUEST_REF),
                "INVALID_PLACEMENT", "cannot be moved under itself");
    }

    @Test
    void applyMoveRejectsAfterSelfBeforeWriting() throws IOException {
        assertInvalidMoveDoesNotOverwrite(moveAfterItselfPatch(HTTP_REQUEST_REF),
                "INVALID_PLACEMENT", "cannot be placed after itself");
    }

    @Test
    void applyMoveRejectsDescendantParentBeforeWriting() throws IOException {
        NestedLoopInput nestedInput = nestedLoopInput(tempDir, "descendant-parent-input.jmx");
        assertInvalidMoveDoesNotOverwrite(
                nestedInput.path(),
                moveUnderParentPatch(nestedInput.loopRef(), "org.apache.jmeter.control.gui.LoopControlPanel", nestedInput.nestedRef()),
                "INVALID_PLACEMENT", "descendant");
    }

    @Test
    void applyMoveRejectsBeforeDescendantBeforeWriting() throws IOException {
        NestedLoopInput nestedInput = nestedLoopInput(tempDir, "before-descendant-input.jmx");
        assertInvalidMoveDoesNotOverwrite(
                nestedInput.path(),
                moveBeforeRefPatch(nestedInput.loopRef(), "org.apache.jmeter.control.gui.LoopControlPanel", nestedInput.nestedRef()),
                "INVALID_PLACEMENT", "cannot be placed before its own descendant");
    }

    @Test
    void applyMoveRejectsAfterDescendantBeforeWriting() throws IOException {
        NestedLoopInput nestedInput = nestedLoopInput(tempDir, "after-descendant-input.jmx");
        assertInvalidMoveDoesNotOverwrite(
                nestedInput.path(),
                moveAfterRefPatch(nestedInput.loopRef(), "org.apache.jmeter.control.gui.LoopControlPanel", nestedInput.nestedRef()),
                "INVALID_PLACEMENT", "cannot be placed after its own descendant");
    }

    @Test
    void applyAddRequiresExactlyOnePlacementBeforeWriting() throws IOException {
        assertInvalidAddDoesNotOverwrite(tempDir, defaultsOnlyHttpAddPatchWithoutPlacement(),
                "INVALID_PLACEMENT", "Use exactly one placement selector");
    }

    @Test
    void applyAddNumericPositionAtChildCountAppends() throws IOException {
        Path output = tempDir.resolve("position-append-output.jmx");

        CliTestResult result = runMainWithLocalRuntime(httpAddPatchAtPosition("1"),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(elementNames(output))
                .containsSubsequence("Synthetic HTTP Request", "HTTP Request");
    }

    @Test
    void applyAddRejectsPositionBeyondChildCountBeforeWriting() throws IOException {
        assertInvalidAddDoesNotOverwrite(tempDir, httpAddPatchAtPosition("9999"),
                "INVALID_PLACEMENT", "Position must be first, last, or an insertion index");
    }

    @Test
    void applyAddRejectsIncompatibleParentBeforeWriting() throws IOException {
        assertInvalidAddDoesNotOverwrite(tempDir, threadGroupUnderThreadGroupPatch(),
                "JMETER_PLACEMENT_REJECTED", "JMeter rejected placement");
    }

    private void assertInvalidMoveDoesNotOverwrite(String patchText, String... expectedMessages) throws IOException {
        assertInvalidMoveDoesNotOverwrite(fixture("simple-http.jmx"), patchText, expectedMessages);
    }

    private void assertInvalidMoveDoesNotOverwrite(Path input, String patchText, String... expectedMessages)
            throws IOException {
        Path output = tempDir.resolve("invalid-move-output-" + System.nanoTime() + ".jmx");
        Files.write(output, "existing".getBytes(StandardCharsets.UTF_8));

        CliTestResult result = runMainWithLocalRuntime(
                patchText, "apply", input.toString(), "--patch", "-", "--out", output.toString(), "--force-out");

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(expectedMessages);
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).isEqualTo("existing");
    }
}
