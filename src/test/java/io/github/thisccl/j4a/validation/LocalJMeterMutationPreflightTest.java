package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalJMeterMutationPreflightTest {
    private LocalPropertyGraphRuntimeContextTestState runtimeContextState;

    @BeforeEach
    void isolateRuntimeContext() throws Exception {
        runtimeContextState = LocalPropertyGraphRuntimeContextTestState.captureAndClear();
    }

    @AfterEach
    void restoreRuntimeContext() throws Exception {
        if (runtimeContextState != null) {
            runtimeContextState.restore();
        }
    }

    @Test
    void opaqueListenerRegistryDiscoveryCompletesDuringPreflightBeforeOperationBudget() throws Exception {
        Path jmeterHome = io.github.thisccl.j4a.TestJMeterRuntime.home();
        Path source = Paths.get("src/test/resources/property-graph-conformance/response-assertion.jmx");
        Path patch = Files.createTempFile("j4a-opaque-listener-preflight-", ".yaml");
        Path target = Files.createTempFile("j4a-opaque-listener-target-", ".jmx");
        Files.delete(target);
        Files.write(patch, opaqueListenerPatch().getBytes(StandardCharsets.UTF_8));
        clearComponentCaches();
        long started = System.nanoTime();

        try {
            LocalJMeterWorkerOperations.snapshot().preflight(
                    LocalJMeterWorkerRequest.applyPatch(source, jmeterHome, patch, target));

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(registryCache()).containsKey(jmeterHome.toAbsolutePath().normalize());
            assertThat(elapsedMillis).isLessThan(TimeUnit.SECONDS.toMillis(60));
            assertThat(target).doesNotExist();
        } finally {
            Files.deleteIfExists(patch);
            Files.deleteIfExists(target);
            clearComponentCaches();
        }
    }

    private static String opaqueListenerPatch() {
        return "changes:\n"
                + "  - add:\n"
                + "      parent: jmx_d62893619b1f\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.visualizers.AssertionVisualizer\n"
                + "      properties:\n"
                + "        - property: TestElement\\.name\n"
                + "          value: QA Opaque Listener\n"
                + "          type: string\n";
    }

    private static void clearComponentCaches() throws Exception {
        clearCache("DEFINITIONS");
        clearCache("CATEGORY_LABELS");
        clearCache("REGISTRIES");
    }

    private static void clearCache(String name) throws Exception {
        Field field = LocalJMeterWorkerComponents.class.getDeclaredField(name);
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(null);
        synchronized (cache) {
            cache.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Path, ?> registryCache() throws Exception {
        Field field = LocalJMeterWorkerComponents.class.getDeclaredField("REGISTRIES");
        field.setAccessible(true);
        return (Map<Path, ?>) field.get(null);
    }
}
