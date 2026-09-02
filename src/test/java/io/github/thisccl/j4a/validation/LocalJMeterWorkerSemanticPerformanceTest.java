package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerSemanticPerformanceTest {
    @Test
    void recordsColdWarmAndCachedWriteEvidenceAcrossCoreComponents() throws Exception {
        Path home = io.github.thisccl.j4a.TestJMeterRuntime.home();
        long initializationStarted = System.nanoTime();
        LocalJMeterWorkerRuntime.initialize(home);
        long initializationNanos = System.nanoTime() - initializationStarted;
        LocalJMeterWorkerComponents.resetWorkerGenerationForTests();
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.current();
        List<ComponentCatalog.ComponentDefinition> definitions = LocalComponentDiscovery.discover(registry);
        io.github.thisccl.j4a.jmx.property.RuntimeContext runtimeContext =
                LocalPropertyGraphRuntimeContext.selected(home);
        List<String> rows = new ArrayList<String>();
        rows.add("component\tworker_initialization_nanos\tcold_detail_nanos\twarm_detail_nanos"
                + "\twarm_write_evidence_nanos\tcold_observations\twarm_observations"
                + "\twarm_write_observations\tcold_gui_constructions\twarm_gui_constructions"
                + "\twarm_write_gui_constructions\tcold_differential_probes\twarm_differential_probes"
                + "\twarm_write_differential_probes\tdescriptor_candidates\ttable_candidates\tcache_size\tstatus");
        int observed = 0;
        for (ComponentCatalog.ComponentDefinition definition : definitions) {
            LocalJMeterMenuRegistry.Entry entry = registry.resolve(definition.component()).orElse(null);
            if (entry == null || entry.kind() != LocalJMeterMenuRegistry.RegistrationKind.GUI_COMPONENT) {
                continue;
            }
            LocalJMeterGuiSemanticInstrumentation.Snapshot before =
                    LocalJMeterGuiSemanticInstrumentation.snapshot();
            long coldStarted = System.nanoTime();
            String status = "ok";
            try {
                LocalJMeterWorkerComponents.discoverDetails(home, definition, runtimeContext);
            } catch (Exception | LinkageError failure) {
                status = failure.getClass().getSimpleName();
            }
            long coldNanos = System.nanoTime() - coldStarted;
            LocalJMeterGuiSemanticInstrumentation.Snapshot afterCold =
                    LocalJMeterGuiSemanticInstrumentation.snapshot();
            long warmStarted = System.nanoTime();
            try {
                LocalJMeterWorkerComponents.discoverDetails(home, definition, runtimeContext);
            } catch (Exception | LinkageError expectedCachedFailure) {
                status = expectedCachedFailure.getClass().getSimpleName();
            }
            long warmNanos = System.nanoTime() - warmStarted;
            LocalJMeterGuiSemanticInstrumentation.Snapshot afterWarm =
                    LocalJMeterGuiSemanticInstrumentation.snapshot();
            LocalJMeterWorkerComponents.semanticMetadata(entry, runtimeContext);
            LocalJMeterGuiSemanticInstrumentation.Snapshot afterWriteWarmup =
                    LocalJMeterGuiSemanticInstrumentation.snapshot();
            long writeStarted = System.nanoTime();
            LocalJMeterGuiSemanticMetadata.Observation observation =
                    LocalJMeterWorkerComponents.semanticMetadata(entry, runtimeContext);
            long writeNanos = System.nanoTime() - writeStarted;
            LocalJMeterGuiSemanticInstrumentation.Snapshot afterWrite =
                    LocalJMeterGuiSemanticInstrumentation.snapshot();
            LocalJMeterGuiSemanticMetadata.Stats stats = observation.stats();
            rows.add(definition.component() + "\t" + initializationNanos + "\t" + coldNanos + "\t"
                    + warmNanos + "\t" + writeNanos + "\t"
                    + delta(afterCold.observationAttempts(), before.observationAttempts()) + "\t"
                    + delta(afterWarm.observationAttempts(), afterCold.observationAttempts()) + "\t"
                    + delta(afterWrite.observationAttempts(), afterWriteWarmup.observationAttempts()) + "\t"
                    + delta(afterCold.guiConstructions(), before.guiConstructions()) + "\t"
                    + delta(afterWarm.guiConstructions(), afterCold.guiConstructions()) + "\t"
                    + delta(afterWrite.guiConstructions(), afterWriteWarmup.guiConstructions()) + "\t"
                    + delta(afterCold.differentialProbes(), before.differentialProbes()) + "\t"
                    + delta(afterWarm.differentialProbes(), afterCold.differentialProbes()) + "\t"
                    + delta(afterWrite.differentialProbes(), afterWriteWarmup.differentialProbes()) + "\t"
                    + stats.descriptorCandidates() + "\t" + stats.tableCandidates() + "\t"
                    + LocalJMeterWorkerComponents.semanticCacheSize() + "\t" + status);
            assertThat(afterWarm.observationAttempts()).isEqualTo(afterCold.observationAttempts());
            assertThat(afterWarm.guiConstructions()).isEqualTo(afterCold.guiConstructions());
            assertThat(afterWarm.differentialProbes()).isEqualTo(afterCold.differentialProbes());
            assertThat(afterWrite.observationAttempts())
                    .as(definition.component() + " status=" + status)
                    .isEqualTo(afterWriteWarmup.observationAttempts());
            assertThat(afterWrite.guiConstructions()).isEqualTo(afterWriteWarmup.guiConstructions());
            assertThat(afterWrite.differentialProbes()).isEqualTo(afterWriteWarmup.differentialProbes());
            observed++;
        }
        Path report = Paths.get("build", "qa", "gui-semantic-cache-performance-5.6.3.tsv");
        Files.createDirectories(report.getParent());
        Files.write(report, rows, StandardCharsets.UTF_8);
        Path interpretation = Paths.get("build", "qa", "gui-semantic-cache-performance-5.6.3.md");
        Files.write(interpretation, java.util.Arrays.asList(
                "# GUI semantic cache performance, JMeter 5.6.3",
                "",
                "Cold detail is the uncached baseline. Warm detail and warm-write evidence lookup are the cached path.",
                "Warm-write evidence is primed explicitly when headless detail materialization fails before semantic observation.",
                "Acceptance is deterministic: every warm observation, GUI construction, and differential-probe delta is zero.",
                "Nanosecond timings are recorded for comparison only and are not pass/fail thresholds.",
                "No JMeter load execution is performed."), StandardCharsets.UTF_8);

        assertThat(observed).isGreaterThan(50);
        assertThat(rows).hasSize(observed + 1);
        assertThat(LocalJMeterWorkerComponents.semanticCacheSize()).isEqualTo(observed);
    }

    private static long delta(long after, long before) {
        return after - before;
    }
}
