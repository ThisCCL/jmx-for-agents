package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerSemanticHotPathTest {
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();
    private static final String HTTP_DEFAULTS =
            "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui";

    @BeforeAll
    static void initializeRuntime() throws Exception {
        LocalJMeterWorkerRuntime.initialize(JMETER_HOME);
    }

    @Test
    void listsObserveNothingAndWarmDetailsConstructAndProbeNothing() throws Exception {
        LocalJMeterWorkerComponents.resetWorkerGenerationForTests();
        LocalJMeterGuiSemanticInstrumentation.Snapshot before =
                LocalJMeterGuiSemanticInstrumentation.snapshot();

        LocalJMeterWorkerComponents.discoverComponents(
                LocalJMeterWorkerRequest.discoverComponents(JMETER_HOME));
        LocalJMeterWorkerComponents.listCategories(LocalJMeterWorkerRequest.listCategories(JMETER_HOME));
        LocalJMeterGuiSemanticInstrumentation.Snapshot afterLists =
                LocalJMeterGuiSemanticInstrumentation.snapshot();

        String cold = LocalJMeterWorkerComponents.componentDetails(
                LocalJMeterWorkerRequest.componentDetails(JMETER_HOME, HTTP_DEFAULTS));
        LocalJMeterGuiSemanticInstrumentation.Snapshot afterCold =
                LocalJMeterGuiSemanticInstrumentation.snapshot();
        String warm = LocalJMeterWorkerComponents.componentDetails(
                LocalJMeterWorkerRequest.componentDetails(JMETER_HOME, HTTP_DEFAULTS));
        LocalJMeterGuiSemanticInstrumentation.Snapshot afterWarm =
                LocalJMeterGuiSemanticInstrumentation.snapshot();
        LocalJMeterMenuRegistry.Entry entry =
                LocalJMeterWorkerComponents.requireLocalEntry(JMETER_HOME, HTTP_DEFAULTS);
        LocalJMeterWorkerComponents.semanticMetadata(
                entry, LocalPropertyGraphRuntimeContext.selected(JMETER_HOME));
        LocalJMeterGuiSemanticInstrumentation.Snapshot afterWarmWriteEvidence =
                LocalJMeterGuiSemanticInstrumentation.snapshot();

        assertThat(afterLists.observationAttempts() - before.observationAttempts()).isZero();
        assertThat(afterLists.guiConstructions() - before.guiConstructions()).isZero();
        assertThat(afterLists.differentialProbes() - before.differentialProbes()).isZero();
        assertThat(afterCold.observationAttempts() - afterLists.observationAttempts()).isEqualTo(1);
        assertThat(afterCold.guiConstructions()).isGreaterThan(afterLists.guiConstructions());
        assertThat(warm).isEqualTo(cold);
        assertThat(afterWarm.observationAttempts()).isEqualTo(afterCold.observationAttempts());
        assertThat(afterWarm.guiConstructions()).isEqualTo(afterCold.guiConstructions());
        assertThat(afterWarm.differentialProbes()).isEqualTo(afterCold.differentialProbes());
        assertThat(afterWarmWriteEvidence.observationAttempts()).isEqualTo(afterWarm.observationAttempts());
        assertThat(afterWarmWriteEvidence.guiConstructions()).isEqualTo(afterWarm.guiConstructions());
        assertThat(afterWarmWriteEvidence.differentialProbes()).isEqualTo(afterWarm.differentialProbes());
        assertThat(LocalJMeterWorkerComponents.semanticCacheSize()).isEqualTo(1);
        assertThat(LocalJMeterWorkerComponents.detailCacheSize()).isEqualTo(1);
    }
}
