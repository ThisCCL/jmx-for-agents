package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class LocalJMeterWorkerOrderParityTest {
    private static final String HTTP_COMPONENT =
            "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui";
    private static final String PARENT_REF = "jmx_19871e6efa95";
    private static final String EXISTING_REF = "jmx_330976848c8e";
    private static final byte[] TARGET_SENTINEL = "local-order-target-sentinel".getBytes(StandardCharsets.UTF_8);
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanFixtures() throws IOException {
        fixtures.delete();
    }

    @Test
    void localWorkerKeepsLastAddsInExactDeclarationOrder() throws IOException {
        Path patch = writePatch("last-order.yml", setName("existing")
                + addAtLast("A") + addAtLast("B") + addAtLast("C"));
        Path target = fixtures.root().resolve("last-order-target.jmx");

        LocalJMeterWorkerResult result = execute(patch, target);

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(childNames(target)).containsExactly("existing", "A", "B", "C");
    }

    @Test
    void localWorkerKeepsRepeatedAfterAddsInExactDeclarationOrder() throws IOException {
        Path patch = writePatch("after-order.yml", setName("anchor")
                + addAfter("X", EXISTING_REF) + addAfter("Y", EXISTING_REF));
        Path target = fixtures.root().resolve("after-order-target.jmx");

        LocalJMeterWorkerResult result = execute(patch, target);

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(childNames(target)).containsExactly("anchor", "X", "Y");
    }

    @Test
    void invalidPlacementReturnsSemanticGuidanceAndPreservesTarget() throws IOException {
        Path patch = writePatch("invalid-placement.yml", addAfter("X", EXISTING_REF)
                + "  - delete:\n      ref: " + EXISTING_REF + "\n");
        Path target = fixtures.root().resolve("invalid-placement-target.jmx");
        Files.write(target, TARGET_SENTINEL);

        LocalJMeterWorkerResult result = execute(patch, target);

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().category()).isEqualTo("operation");
        assertThat(result.response().message()).contains("anchor", "moved or deleted");
        assertThat(result.response().suggestedAction()).contains("inspect the JMX", "retry");
        assertThat(Files.readAllBytes(target)).containsExactly(TARGET_SENTINEL);
    }

    @Test
    void selectedRuntimeProvesExactSaveServiceSiblingAndSubtreeOrder() throws Exception {
        assertThat(JMETER_HOME.resolve("bin/ApacheJMeter.jar")).isRegularFile();
        LocalJMeterWorkerRuntime.initialize(JMETER_HOME);
        LocalJMeterWorkerOrderCapability capability = LocalJMeterWorkerOrderCapability.production();

        capability.requireProven();
        capability.requireProven();
        System.out.println("ORDER_PROOF runtime=" + JMETER_HOME + " exactSiblingSubtree=true memoized=true");
    }

    @Test
    void failedOrderProofIsMemoizedPrecedesMutationWorkAndLeavesReadFocusUsable() throws Exception {
        Path source = tempDir.resolve("order-gate-source.jmx");
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        Path missingSource = tempDir.resolve("missing-source.jmx");
        Path target = tempDir.resolve("order-gate-target.jmx");
        Files.write(target, TARGET_SENTINEL);
        AtomicInteger proofAttempts = new AtomicInteger();
        LocalJMeterWorkerOrderCapability capability = new LocalJMeterWorkerOrderCapability(() -> {
            proofAttempts.incrementAndGet();
            throw new IOException("injected encounter-order drift");
        });
        LocalJMeterWorkerOperations operations = LocalJMeterWorkerOperations.session(
                new SessionReferenceRegistry(), capability);
        LocalJMeterWorkerRequest read = LocalJMeterWorkerRequest.renderReadData(
                source, JMETER_HOME, "5", null, "NONE", "false");
        String reference = firstReference(operations.execute(read));

        assertOrderFailure(() -> operations.execute(LocalJMeterWorkerRequest.setProperty(
                missingSource,
                JMETER_HOME,
                target,
                "stale-ref",
                "[TestElement.name]",
                "changed",
                "string")));
        assertOrderFailure(() -> operations.execute(LocalJMeterWorkerRequest.applyPatchYaml(
                missingSource,
                JMETER_HOME,
                "changes:\n  - delete:\n      ref: stale-ref\n",
                target,
                true)));
        assertOrderFailure(() -> operations.execute(LocalJMeterWorkerRequest.initJmx(
                target, JMETER_HOME, "uncommitted", "uncommitted", "2", "NONE")));

        String focused = operations.execute(LocalJMeterWorkerRequest.renderReadData(
                source, JMETER_HOME, "5", reference, "NONE", "false"));
        assertThat(firstReference(focused)).isEqualTo(reference);
        assertThat(proofAttempts).hasValue(1);
        assertThat(Files.readAllBytes(target)).containsExactly(TARGET_SENTINEL);
        assertThat(Files.exists(missingSource)).isFalse();
        System.out.println("ORDER_GATE failureCode=MCP_REF_ORDER_UNPROVEN proofAttempts="
                + proofAttempts.get() + " focusAvailable=true targetPreserved=true sourceUntouched=true");
    }

    private LocalJMeterWorkerResult execute(Path patch, Path target) throws IOException {
        return LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60))
                .execute(LocalJMeterWorkerRequest.applyPatch(
                        fixtures.root().resolve("basic.jmx"), fixtures.localHome(), patch, target));
    }

    private Path writePatch(String name, String changes) throws IOException {
        fixtures.ensure();
        Path patch = fixtures.root().resolve(name);
        Files.write(patch, ("changes:\n" + changes).getBytes(StandardCharsets.UTF_8));
        return patch;
    }

    private List<String> childNames(Path jmx) throws IOException {
        LocalJMeterWorkerResult result = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60))
                .execute(LocalJMeterWorkerRequest.renderReadData(
                        jmx, fixtures.localHome(), "5", null, "NONE", "false"));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> document = mapping(new Yaml().load(result.response().payload()));
        Map<String, Object> root = document.containsKey("root") ? mapping(document.get("root")) : document;
        return childNames(findByName(root, "Synthetic Thread Group"));
    }

    private static Map<String, Object> findByName(Map<String, Object> node, String name) {
        if (name.equals(node.get("name"))) {
            return node;
        }
        Object children = node.get("children");
        if (children instanceof List<?>) {
            for (Object child : (List<?>) children) {
                Map<String, Object> found = findByName(mapping(child), name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> childNames(Map<String, Object> parent) {
        assertThat(parent).isNotNull();
        List<String> names = new ArrayList<>();
        for (Object child : (List<?>) parent.get("children")) {
            names.add((String) mapping(child).get("name"));
        }
        return names;
    }

    private static void assertOrderFailure(ThrowingOperation operation) {
        assertThatThrownBy(operation::execute)
                .isInstanceOfSatisfying(ReferenceFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(ReferenceFailure.Reason.ORDER_UNPROVEN);
                    assertThat(failure.code()).isEqualTo("MCP_REF_ORDER_UNPROVEN");
                    assertThat(failure.category()).isEqualTo("runtime");
                    assertThat(failure.suggestedAction())
                            .contains("conforming JMeter runtime/home", "stateless CLI write/read recovery");
                });
    }

    private static String firstReference(String yaml) {
        List<String> references = new ArrayList<String>();
        collectReferences(new Yaml().load(yaml), references);
        assertThat(references).isNotEmpty();
        return references.get(0);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static String setName(String name) {
        return "  - set:\n      ref: " + EXISTING_REF + "\n      component: " + HTTP_COMPONENT + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: " + name + "\n          type: string\n";
    }

    private static String addAtLast(String name) {
        return add(name, "      position: last\n");
    }

    private static String addAfter(String name, String anchorRef) {
        return add(name, "      after: " + anchorRef + "\n");
    }

    private static String add(String name, String placement) {
        return "  - add:\n      parent: " + PARENT_REF + "\n" + placement
                + "      component: " + HTTP_COMPONENT + "\n      properties:\n"
                + "        - property: [TestElement.name]\n          value: " + name + "\n          type: string\n";
    }

    private interface ThrowingOperation {
        void execute() throws Exception;
    }
}
