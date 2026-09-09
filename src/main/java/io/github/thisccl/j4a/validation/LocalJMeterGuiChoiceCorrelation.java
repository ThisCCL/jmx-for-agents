package io.github.thisccl.j4a.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;

final class LocalJMeterGuiChoiceCorrelation {
    private LocalJMeterGuiChoiceCorrelation() {
    }

    static java.util.Optional<LocalJMeterGuiSemanticMetadata.ScalarDescriptor> correlate(
            String guiClassName,
            String version,
            LocalJMeterGuiSemanticTraversal.ChoiceCandidate expected,
            ProbeBudget budget) {
        JMeterGUIComponent probeGui = null;
        try {
            if (!budget.consume()) return java.util.Optional.empty();
            probeGui = create(guiClassName);
            probeGui.clearGui();
            List<LocalJMeterGuiSemanticTraversal.ChoiceCandidate> matches = matchingChoices(
                    LocalJMeterGuiSemanticTraversal.inspect(probeGui, version).choices(), expected);
            if (matches.size() != 1) return java.util.Optional.empty();
            LocalJMeterGuiSemanticTraversal.ChoiceCandidate choice = matches.get(0);
            int initialSelectedIndex = choice.selectedIndex();
            ArrayList<Map<String, ScalarSample>> snapshots = new ArrayList<Map<String, ScalarSample>>();
            ArrayList<TestElement> variants = new ArrayList<TestElement>();
            for (int index = 0; index < choice.size(); index++) {
                if (!budget.consume()) return java.util.Optional.empty();
                choice.select(index);
                TestElement variant = probeGui.createTestElement();
                variants.add(variant);
                snapshots.add(scalarSnapshot(variant));
            }
            if (snapshots.isEmpty() || snapshots.contains(Collections.<String, ScalarSample>emptyMap())) {
                return java.util.Optional.empty();
            }
            LinkedHashSet<String> sharedProperties = new LinkedHashSet<String>(snapshots.get(0).keySet());
            for (int index = 1; index < snapshots.size(); index++) {
                sharedProperties.retainAll(snapshots.get(index).keySet());
            }
            ArrayList<LocalJMeterGuiSemanticMetadata.ScalarDescriptor> candidates =
                    new ArrayList<LocalJMeterGuiSemanticMetadata.ScalarDescriptor>();
            for (String property : sharedProperties) {
                String type = null;
                LinkedHashSet<Object> values = new LinkedHashSet<Object>();
                ArrayList<LocalJMeterGuiSemanticMetadata.ValueOption> options =
                        new ArrayList<LocalJMeterGuiSemanticMetadata.ValueOption>();
                boolean compatible = true;
                for (int index = 0; index < snapshots.size(); index++) {
                    ScalarSample sample = snapshots.get(index).get(property);
                    if (type == null) type = sample.type;
                    if (!type.equals(sample.type) || !copyableScalar(sample.value)
                            || !values.add(sample.value)) {
                        compatible = false;
                        break;
                    }
                    options.add(new LocalJMeterGuiSemanticMetadata.ValueOption(
                            sample.value, choice.label(index)));
                }
                if (!compatible || values.size() != choice.size()
                        || !reproducesChoice(
                                guiClassName, version, expected, property, variants, budget)) {
                    continue;
                }
                Object defaultValue = initialSelectedIndex >= 0 && initialSelectedIndex < snapshots.size()
                        ? snapshots.get(initialSelectedIndex).get(property).value : null;
                candidates.add(new LocalJMeterGuiSemanticMetadata.ScalarDescriptor(
                        property, type, defaultValue, options));
            }
            return candidates.size() == 1
                    ? java.util.Optional.of(candidates.get(0)) : java.util.Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return java.util.Optional.empty();
        } finally {
            if (probeGui != null) release(probeGui);
        }
    }

    private static boolean reproducesChoice(
            String guiClassName,
            String version,
            LocalJMeterGuiSemanticTraversal.ChoiceCandidate expected,
            String property,
            List<TestElement> variants,
            ProbeBudget budget) throws ReflectiveOperationException {
        for (int index = 0; index < variants.size(); index++) {
            if (!budget.consume()) return false;
            JMeterProperty sampled = variants.get(index).getPropertyOrNull(property);
            if (sampled == null) return false;
            TestElement isolated = materialize(guiClassName);
            isolated.setProperty(sampled.clone());
            JMeterGUIComponent configured = create(guiClassName);
            try {
                configured.clearGui();
                configured.configure(isolated);
                List<LocalJMeterGuiSemanticTraversal.ChoiceCandidate> matches = matchingChoices(
                        LocalJMeterGuiSemanticTraversal.inspect(configured, version).choices(), expected);
                if (matches.size() != 1 || matches.get(0).selectedIndex() != index) return false;
            } finally {
                release(configured);
            }
        }
        return true;
    }

    private static Map<String, ScalarSample> scalarSnapshot(TestElement element) {
        LinkedHashMap<String, ScalarSample> snapshot = new LinkedHashMap<String, ScalarSample>();
        PropertyIterator properties = element.propertyIterator();
        int count = 0;
        while (properties.hasNext()) {
            if (count++ >= LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxOutputRows) {
                return Collections.emptyMap();
            }
            JMeterProperty property = properties.next();
            String type = scalarType(property);
            if (type != null) {
                snapshot.put(property.getName(), new ScalarSample(type, scalarValue(property)));
            }
        }
        return snapshot;
    }

    private static List<LocalJMeterGuiSemanticTraversal.ChoiceCandidate> matchingChoices(
            List<LocalJMeterGuiSemanticTraversal.ChoiceCandidate> candidates,
            LocalJMeterGuiSemanticTraversal.ChoiceCandidate expected) {
        ArrayList<LocalJMeterGuiSemanticTraversal.ChoiceCandidate> matches =
                new ArrayList<LocalJMeterGuiSemanticTraversal.ChoiceCandidate>();
        for (LocalJMeterGuiSemanticTraversal.ChoiceCandidate candidate : candidates) {
            if (expected.sameSignature(candidate)) matches.add(candidate);
        }
        return matches;
    }

    private static TestElement materialize(String guiClassName) throws ReflectiveOperationException {
        JMeterGUIComponent gui = create(guiClassName);
        try {
            gui.clearGui();
            return gui.createTestElement();
        } finally {
            release(gui);
        }
    }

    private static String scalarType(JMeterProperty property) {
        if (property instanceof StringProperty) return "string";
        if (property instanceof BooleanProperty) return "boolean";
        if (property instanceof IntegerProperty) return "int";
        if (property instanceof LongProperty) return "long";
        if (property instanceof FloatProperty) return "float";
        if (property instanceof DoubleProperty) return "double";
        return null;
    }

    private static Object scalarValue(JMeterProperty property) {
        if (property instanceof StringProperty) return property.getStringValue();
        if (property instanceof BooleanProperty) return Boolean.valueOf(property.getBooleanValue());
        if (property instanceof IntegerProperty) return Integer.valueOf(property.getIntValue());
        if (property instanceof LongProperty) return Long.valueOf(property.getLongValue());
        if (property instanceof FloatProperty) return Float.valueOf(property.getFloatValue());
        if (property instanceof DoubleProperty) return Double.valueOf(property.getDoubleValue());
        throw new IllegalArgumentException("property is not a supported scalar");
    }

    private static boolean copyableScalar(Object value) {
        if (value instanceof Float) return Float.isFinite(((Float) value).floatValue());
        if (value instanceof Double) return Double.isFinite(((Double) value).doubleValue());
        return true;
    }

    private static JMeterGUIComponent create(String guiClassName) {
        try {
            Class<?> guiClass = Class.forName(guiClassName, true,
                    LocalJMeterValidationWorker.class.getClassLoader());
            JMeterGUIComponent gui = (JMeterGUIComponent) guiClass.getDeclaredConstructor().newInstance();
            LocalJMeterGuiSemanticInstrumentation.guiConstructed();
            return gui;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("JMeter GUI construction failed", exception);
        }
    }

    private static void release(JMeterGUIComponent gui) {
        if (gui instanceof TestBeanGUI) {
            org.apache.jmeter.util.JMeterUtils.removeLocaleChangeListener((TestBeanGUI) gui);
        }
    }

    private static final class ScalarSample {
        private final String type;
        private final Object value;

        private ScalarSample(String type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    static final class ProbeBudget {
        private final int maximumProbes;
        private final long maximumElapsedNanos;
        private final long started;
        private int probes;
        private LocalJMeterGuiSemanticMetadata.FailureReason failureReason;

        ProbeBudget(LocalJMeterGuiSemanticMetadata.Budget budget, long started) {
            this.maximumProbes = budget.maxChoiceProbes;
            this.maximumElapsedNanos = budget.maxElapsedNanos;
            this.started = started;
        }

        boolean consume() {
            if (elapsedExceeded()) return false;
            if (probes >= maximumProbes) {
                failureReason = LocalJMeterGuiSemanticMetadata.FailureReason.CHOICE_PROBE_BUDGET;
                return false;
            }
            probes++;
            return true;
        }

        boolean exhausted() {
            return failureReason != null || elapsedExceeded();
        }

        LocalJMeterGuiSemanticMetadata.Failure failure() {
            elapsedExceeded();
            return failureReason == null ? null : new LocalJMeterGuiSemanticMetadata.Failure(
                    failureReason, failureReason
                            == LocalJMeterGuiSemanticMetadata.FailureReason.CHOICE_PROBE_BUDGET
                            ? String.valueOf(probes) : String.valueOf(System.nanoTime() - started));
        }

        private boolean elapsedExceeded() {
            if (System.nanoTime() - started <= maximumElapsedNanos) return false;
            failureReason = LocalJMeterGuiSemanticMetadata.FailureReason.ELAPSED_TIME_BUDGET;
            return true;
        }
    }
}
