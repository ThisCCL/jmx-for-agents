package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class J4aMcpRequestIsolationTest {
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();

    @TempDir
    Path tempDir;

    @Test
    void mutablePlanNeverLeaksAcrossCalls() throws Exception {
        Path jmx = tempDir.resolve("plan.jmx");
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), jmx);
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult first = pool.execute(LocalJMeterWorkerRequest.renderReadData(jmx, JMETER_HOME));
            String firstRef = firstReference(first);
            String edited = new String(Files.readAllBytes(jmx), StandardCharsets.UTF_8)
                    .replace("Synthetic Test Plan", "EXTERNAL EDIT");
            Files.write(jmx, edited.getBytes(StandardCharsets.UTF_8));
            LocalJMeterWorkerResult second = pool.execute(LocalJMeterWorkerRequest.renderReadData(jmx, JMETER_HOME));
            String secondRef = firstReference(second);

            assertThat(first.response().success()).isTrue();
            assertThat(first.response().payload()).doesNotContain("EXTERNAL EDIT");
            assertThat(second.response().success()).isTrue();
            assertThat(second.response().payload()).contains("EXTERNAL EDIT");
            assertThat(secondRef).isNotEqualTo(firstRef);
            assertUnavailableRef(pool.execute(read(jmx, firstRef)));
        } finally {
            pool.close();
        }
    }

    @Test
    void focusedReadInvalidatesExternallyChangedDocumentBeforeUnfocusedRecovery() throws Exception {
        Path jmx = tempDir.resolve("focused-stale.jmx");
        byte[] original = Files.readAllBytes(Paths.get("src/test/resources/fixtures/simple-http.jmx"));
        Files.write(jmx, original);
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult initial = pool.execute(read(jmx, null));
            String staleRef = firstReference(initial);
            String externallyEdited = new String(original, StandardCharsets.UTF_8)
                    .replace("example.test", "external.example");
            Files.write(jmx, externallyEdited.getBytes(StandardCharsets.UTF_8));

            LocalJMeterWorkerResult changedFocus = pool.execute(read(jmx, staleRef));
            assertUnavailableRef(changedFocus);

            Files.write(jmx, original);
            LocalJMeterWorkerResult restoredFocus = pool.execute(read(jmx, staleRef));
            assertUnavailableRef(restoredFocus);

            LocalJMeterWorkerResult recovered = pool.execute(read(jmx, null));
            String recoveredRef = firstReference(recovered);
            assertThat(recoveredRef).matches("[A-Za-z0-9_-]{16}").isNotEqualTo(staleRef);
            assertThat(firstReference(pool.execute(read(jmx, recoveredRef)))).isEqualTo(recoveredRef);
        } finally {
            pool.close();
        }
    }

    @Test
    void setPreservesSourceRefsInPlaceAndAcrossCopyWhileReplacingTargetRefs() throws Exception {
        Path source = tempDir.resolve("set-source.jmx");
        Path target = tempDir.resolve("set-target.jmx");
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), target);
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            List<String> sourceRefs = references(pool.execute(read(source, null)));
            String sourceRef = sourceRefs.get(sourceRefs.size() - 1);
            String priorTargetRef = firstReference(pool.execute(read(target, null)));

            assertSuccessfulSet(pool, source, source, sourceRef,
                    "HTTPSampler.domain", "in-place.example", "STRING");
            assertFocusedNode(pool.execute(read(source, sourceRef)), sourceRef, "Synthetic HTTP Request");
            assertSuccessfulSet(pool, source, source, sourceRef,
                    "TestElement.name", "Renamed Request", "STRING");
            assertFocusedNode(pool.execute(read(source, sourceRef)), sourceRef, "Renamed Request");
            assertSuccessfulSet(pool, source, source, sourceRef,
                    "TestElement.enabled", "false", "BOOLEAN");
            assertFocusedNode(pool.execute(read(source, sourceRef)), sourceRef, "Renamed Request");

            assertSuccessfulSet(pool, source, target, sourceRef,
                    "HTTPSampler.domain", "copy.example", "STRING");
            assertFocusedNode(pool.execute(read(source, sourceRef)), sourceRef, "Renamed Request");
            assertUnavailableRef(pool.execute(read(target, priorTargetRef)));
            assertThat(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))
                    .contains("in-place.example").doesNotContain("copy.example");
            assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
                    .contains("copy.example");
        } finally {
            pool.close();
        }
    }

    @Test
    void focusedReadInvalidatesMissingDirectoryAndUnreadableSourcesOnly() throws Exception {
        Path affected = tempDir.resolve("affected-source.jmx");
        Path companion = tempDir.resolve("companion-source.jmx");
        byte[] original = Files.readAllBytes(Paths.get("src/test/resources/fixtures/simple-http.jmx"));
        Files.write(affected, original);
        Files.write(companion, original);
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            String companionRef = firstReference(pool.execute(read(companion, null)));

            String deletedRef = firstReference(pool.execute(read(affected, null)));
            Files.delete(affected);
            assertUnavailableRef(pool.execute(read(affected, deletedRef)));
            Files.write(affected, original);
            assertUnavailableRef(pool.execute(read(affected, deletedRef)));
            assertRefResolves(pool, companion, JMETER_HOME, companionRef);

            String directoryRef = firstReference(pool.execute(read(affected, null)));
            Files.delete(affected);
            Files.createDirectory(affected);
            assertUnavailableRef(pool.execute(read(affected, directoryRef)));
            Files.delete(affected);
            Files.write(affected, original);
            assertUnavailableRef(pool.execute(read(affected, directoryRef)));
            assertRefResolves(pool, companion, JMETER_HOME, companionRef);

            String unreadableRef = firstReference(pool.execute(read(affected, null)));
            Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(affected);
            try {
                Files.setPosixFilePermissions(affected, Collections.<PosixFilePermission>emptySet());
                assertThat(Files.isReadable(affected)).isFalse();
                assertUnavailableRef(pool.execute(read(affected, unreadableRef)));
            } finally {
                Files.setPosixFilePermissions(affected, originalPermissions);
            }
            assertUnavailableRef(pool.execute(read(affected, unreadableRef)));
            assertRefResolves(pool, companion, JMETER_HOME, companionRef);

            String recoveredRef = firstReference(pool.execute(read(affected, null)));
            assertThat(recoveredRef)
                    .isNotEqualTo(deletedRef)
                    .isNotEqualTo(directoryRef)
                    .isNotEqualTo(unreadableRef);
            assertRefResolves(pool, affected, JMETER_HOME, recoveredRef);
        } finally {
            pool.close();
        }
    }

    @Test
    void unknownRefsStayNeutralAcrossPathHomeAndWorkerGenerationBoundaries() throws Exception {
        Path first = tempDir.resolve("binding-first.jmx");
        Path second = tempDir.resolve("binding-second.jmx");
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), first);
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), second);
        String firstRef;
        J4aMcpRuntimePool initialPool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult firstRead = initialPool.execute(read(first, null));
            List<String> firstRefs = references(firstRead);
            firstRef = firstRefs.get(0);
            String focusedRef = firstRefs.get(firstRefs.size() - 1);
            String secondRef = firstReference(initialPool.execute(read(second, null)));
            assertFocusedNode(initialPool.execute(read(first, focusedRef)), focusedRef, "Synthetic HTTP Request");

            assertUnavailableRef(initialPool.execute(read(first, "AAAAAAAAAAAAAAAA")));
            assertUnavailableRef(initialPool.execute(read(first, "jmx_330976848c8e")));
            assertRefResolves(initialPool, first, JMETER_HOME, firstRef);
            assertFocusedNode(initialPool.execute(read(first, focusedRef)), focusedRef, "Synthetic HTTP Request");
            assertRefResolves(initialPool, second, JMETER_HOME, secondRef);

            assertUnavailableRef(initialPool.execute(read(second, firstRef)));
            assertRefResolves(initialPool, first, JMETER_HOME, firstRef);
            assertRefResolves(initialPool, second, JMETER_HOME, secondRef);

            Path otherHome = tempDir.resolve("alternate-supported-home");
            createReal563ShapeHome(otherHome);
            assertUnavailableRef(initialPool.execute(read(first, otherHome, firstRef)));
            assertRefResolves(initialPool, first, JMETER_HOME, firstRef);
        } finally {
            initialPool.close();
        }

        J4aMcpRuntimePool replacementPool = new J4aMcpRuntimePool();
        try {
            assertUnavailableRef(replacementPool.execute(read(first, firstRef)));
            String replacementRef = firstReference(replacementPool.execute(read(first, null)));
            assertThat(replacementRef).matches("[A-Za-z0-9_-]{16}").isNotEqualTo(firstRef);
            assertRefResolves(replacementPool, first, JMETER_HOME, replacementRef);
        } finally {
            replacementPool.close();
        }
    }

    @Test
    void installationPropertiesStaySnapshottedUntilWorkerRestart() throws Exception {
        Path home = tempDir.resolve("synthetic-runtime-home");
        createReal563ShapeHome(home);
        Path jmx = tempDir.resolve("snapshot-plan.jmx");
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), jmx);
        Path jmeterProperties = home.resolve("bin/jmeter.properties");
        J4aMcpRuntimePool firstPool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult first = firstPool.execute(LocalJMeterWorkerRequest.renderReadData(jmx, home));
            Files.delete(jmeterProperties);
            String edited = new String(Files.readAllBytes(jmx), StandardCharsets.UTF_8)
                    .replace("Synthetic Test Plan", "FRESH PLAN AFTER PROPERTY CHANGE");
            Files.write(jmx, edited.getBytes(StandardCharsets.UTF_8));
            LocalJMeterWorkerResult sameWorker = firstPool.execute(
                    LocalJMeterWorkerRequest.renderReadData(jmx, home));

            assertThat(first.response().success()).isTrue();
            assertThat(sameWorker.response().success()).isTrue();
            assertThat(sameWorker.response().payload()).contains("FRESH PLAN AFTER PROPERTY CHANGE");
        } finally {
            firstPool.close();
        }

        J4aMcpRuntimePool restartedPool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult restarted = restartedPool.execute(
                    LocalJMeterWorkerRequest.renderReadData(jmx, home));

            assertThat(restarted.response().success()).isFalse();
            assertThat(restarted.response().disposition().name()).isEqualTo("FATAL_FAILURE");
        } finally {
            restartedPool.close();
        }
    }

    private static void createReal563ShapeHome(Path home) throws Exception {
        Class<?> fixtures = Class.forName(
                "io.github.thisccl.j4a.validation.DefaultLocalProfileHomeFixtures");
        java.lang.reflect.Method createHome = fixtures.getDeclaredMethod("createHome", Path.class, boolean.class);
        createHome.setAccessible(true);
        createHome.invoke(null, home, Boolean.TRUE);
    }

    private static LocalJMeterWorkerRequest read(Path source, String reference) {
        return read(source, JMETER_HOME, reference);
    }

    private static LocalJMeterWorkerRequest read(Path source, Path home, String reference) {
        return LocalJMeterWorkerRequest.renderReadData(
                source, home, "5", reference, "NONE", "false");
    }

    private static void assertUnavailableRef(LocalJMeterWorkerResult result) {
        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("MCP_REF_NOT_FOUND");
        assertThat(result.response().category()).isEqualTo("usage");
        assertThat(result.response().suggestedAction()).contains("same file", "same JMeter home", "fresh ref");
    }

    private static String firstReference(LocalJMeterWorkerResult result) {
        List<String> references = references(result);
        assertThat(references).isNotEmpty();
        return references.get(0);
    }

    private static List<String> references(LocalJMeterWorkerResult result) {
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        List<String> references = new ArrayList<String>();
        collectReferences(new Yaml().load(result.response().payload()), references);
        return references;
    }

    private static void assertRefResolves(
            J4aMcpRuntimePool pool, Path source, Path home, String reference) {
        assertThat(references(pool.execute(read(source, home, reference)))).contains(reference);
    }

    private static void assertSuccessfulSet(
            J4aMcpRuntimePool pool,
            Path source,
            Path target,
            String reference,
            String property,
            String value,
            String type) {
        LocalJMeterWorkerResult result = pool.execute(LocalJMeterWorkerRequest.setProperty(
                source, JMETER_HOME, target, reference,
                McpJson.write(Collections.singletonList(property)), value, type));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
    }

    private static void assertFocusedNode(
            LocalJMeterWorkerResult result, String reference, String expectedName) {
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> document = mapping(new Yaml().load(result.response().payload()));
        assertThat(mapping(document.get("focus")))
                .containsEntry("ref", reference)
                .containsEntry("name", expectedName);
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
        return (Map<String, Object>) value;
    }
}
