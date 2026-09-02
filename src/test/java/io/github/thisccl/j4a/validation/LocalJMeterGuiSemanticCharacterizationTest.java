package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.junit.jupiter.api.Test;

class LocalJMeterGuiSemanticCharacterizationTest {
    @Test
    void recordsColdSemanticTraversalAcrossCoreJMeterMenuComponents() throws Exception {
        Path jmeterHome = io.github.thisccl.j4a.TestJMeterRuntime.home();
        LocalJMeterWorkerRuntime.initialize(jmeterHome);
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.current();
        List<String> rows = new ArrayList<>();
        rows.add("component\tconstruction_nanos\tvisited_objects\treflected_fields\tmaximum_depth"
                + "\tdescriptor_candidates\ttable_candidates\temitted_scalars\tstatus\tfailures");
        int observed = 0;
        int maxObjects = 0;
        int maxFields = 0;
        int maxDepth = 0;
        int maxDescriptors = 0;
        int maxTables = 0;
        int maxOutput = 0;
        long maxConstructionNanos = 0L;
        for (LocalJMeterMenuRegistry.Entry entry : registry.entries()) {
            if (entry.kind() != LocalJMeterMenuRegistry.RegistrationKind.GUI_COMPONENT) continue;
            long started = System.nanoTime();
            JMeterGUIComponent gui = null;
            try {
                Class<?> type = Class.forName(entry.menuClassName(), true,
                        LocalJMeterValidationWorker.class.getClassLoader());
                gui = (JMeterGUIComponent) type.getDeclaredConstructor().newInstance();
                long constructionNanos = System.nanoTime() - started;
                int windowsAfterConstruction = Window.getWindows().length;
                LocalJMeterGuiSemanticMetadata.Observation observation =
                        LocalJMeterGuiSemanticTraversal.observe(gui, "5.6.3");
                assertThat(Window.getWindows()).hasSize(windowsAfterConstruction);
                LocalJMeterGuiSemanticMetadata.Stats stats = observation.stats();
                observed++;
                maxConstructionNanos = Math.max(maxConstructionNanos, constructionNanos);
                maxObjects = Math.max(maxObjects, stats.visitedObjects());
                maxFields = Math.max(maxFields, stats.reflectedFields());
                maxDepth = Math.max(maxDepth, stats.maximumDepth());
                maxDescriptors = Math.max(maxDescriptors, stats.descriptorCandidates());
                maxTables = Math.max(maxTables, stats.tableCandidates());
                maxOutput = Math.max(maxOutput, observation.scalarDescriptors().size());
                rows.add(entry.menuClassName() + "\t" + constructionNanos + "\t"
                        + stats.visitedObjects() + "\t" + stats.reflectedFields() + "\t"
                        + stats.maximumDepth() + "\t"
                        + stats.descriptorCandidates() + "\t" + stats.tableCandidates() + "\t"
                        + observation.scalarDescriptors().size() + "\t"
                        + (observation.runtimeProven() ? "runtime-proven" : "runtime-metadata-unavailable")
                        + "\t" + failureNames(observation));
            } catch (Throwable failure) {
                rows.add(entry.menuClassName() + "\t" + (System.nanoTime() - started)
                        + "\t0\t0\t0\t0\t0\t0\tconstruction-unavailable\t"
                        + failure.getClass().getName());
            } finally {
                if (gui instanceof TestBeanGUI) {
                    org.apache.jmeter.util.JMeterUtils.removeLocaleChangeListener((TestBeanGUI) gui);
                }
                for (Window window : Window.getWindows()) {
                    if (!window.isVisible()) window.dispose();
                }
            }
        }
        rows.add("SUMMARY\t" + maxConstructionNanos + "\t" + maxObjects + "\t" + maxFields
                + "\t" + maxDepth + "\t" + maxDescriptors + "\t" + maxTables + "\t" + maxOutput
                + "\tobserved=" + observed + "\tcore-menu-5.6.3");
        Path report = Paths.get("build", "qa", "gui-semantic-characterization-5.6.3.tsv");
        Files.createDirectories(report.getParent());
        Files.write(report, rows, StandardCharsets.UTF_8);

        assertThat(observed).isGreaterThan(50);
        assertThat(rows).hasSizeGreaterThan(51);
        assertThat(Window.getWindows()).noneMatch(Window::isVisible);
        assertThat(maxObjects).isLessThan(LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxObjects);
        assertThat(maxFields).isLessThan(LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxFields);
        assertThat(maxDescriptors).isLessThan(LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxDescriptors);
        assertThat(maxTables).isLessThan(LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxTableCandidates);
        assertThat(maxOutput).isLessThan(LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxOutputRows);
    }

    private static String failureNames(LocalJMeterGuiSemanticMetadata.Observation observation) {
        StringBuilder names = new StringBuilder();
        for (LocalJMeterGuiSemanticMetadata.Failure failure : observation.failures()) {
            if (names.length() > 0) names.append(',');
            names.append(failure.reason().name());
        }
        return names.toString();
    }
}
