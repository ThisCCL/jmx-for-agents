package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowEvidence;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

final class LocalJMeterGuiSemanticCorrelation {
    private static final AtomicLong NEXT_SENTINEL = new AtomicLong();

    private LocalJMeterGuiSemanticCorrelation() {
    }

    static LocalJMeterGuiSemanticMetadata.Observation observe(
            String guiClassName, String version, RuntimeContext runtimeContext) {
        LocalJMeterGuiSemanticTraversal.TraversalResult initial;
        JMeterGUIComponent gui;
        try {
            gui = create(guiClassName);
        } catch (RuntimeException exception) {
            LocalJMeterGuiSemanticMetadata.Failure failure =
                    new LocalJMeterGuiSemanticMetadata.Failure(
                            LocalJMeterGuiSemanticMetadata.FailureReason.ROW_CONSTRUCTOR,
                            exception.getClass().getName());
            return new LocalJMeterGuiSemanticMetadata.Observation(
                    java.util.Collections.<LocalJMeterGuiSemanticMetadata.ScalarDescriptor>emptyList(),
                    java.util.Collections.singletonList(failure),
                    new LocalJMeterGuiSemanticMetadata.Stats(0, 0, 0, 0, 0, 0L));
        }
        try {
            gui.clearGui();
            initial = LocalJMeterGuiSemanticTraversal.inspect(gui, version);
        } finally {
            release(gui);
        }
        List<LocalJMeterGuiSemanticMetadata.Failure> failures =
                new ArrayList<LocalJMeterGuiSemanticMetadata.Failure>(initial.observation().failures());
        List<LocalJMeterGuiSemanticMetadata.StructuredRowConsumer> consumers =
                new ArrayList<LocalJMeterGuiSemanticMetadata.StructuredRowConsumer>();
        for (LocalJMeterGuiSemanticTraversal.TableCandidate candidate : initial.tables()) {
            LocalJMeterGuiSemanticInstrumentation.differentialProbeStarted();
            correlate(guiClassName, version, runtimeContext, candidate, failures).ifPresent(consumers::add);
        }
        if (initial.observation().scalarDescriptors().size() + consumers.size()
                > LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxOutputRows) {
            failures.add(new LocalJMeterGuiSemanticMetadata.Failure(
                    LocalJMeterGuiSemanticMetadata.FailureReason.OUTPUT_BUDGET,
                    String.valueOf(consumers.size())));
            consumers.clear();
        }
        return new LocalJMeterGuiSemanticMetadata.Observation(
                initial.observation().scalarDescriptors(), consumers, failures, initial.observation().stats());
    }

    private static java.util.Optional<LocalJMeterGuiSemanticMetadata.StructuredRowConsumer> correlate(
            String guiClassName,
            String version,
            RuntimeContext runtimeContext,
            LocalJMeterGuiSemanticTraversal.TableCandidate expected,
            List<LocalJMeterGuiSemanticMetadata.Failure> failures) {
        try {
            TestElement baseline = materialize(guiClassName);
            JMeterGUIComponent probeGui = create(guiClassName);
            try {
                probeGui.clearGui();
                List<LocalJMeterGuiSemanticTraversal.TableCandidate> matches = matchingCandidates(
                        LocalJMeterGuiSemanticTraversal.inspect(probeGui, version).tables(), expected);
                if (matches.size() != 1) {
                    fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.CANDIDATE_AMBIGUOUS,
                            expected.objectClass().getName());
                    return java.util.Optional.empty();
                }
                ScalarProbe probeResult = addVerifiedSentinel(matches.get(0));
                if (probeResult == null) {
                    fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.SENTINEL_UNAVAILABLE,
                            expected.objectClass().getName());
                    return java.util.Optional.empty();
                }
                TestElement probe = probeGui.createTestElement();
                List<String> changed = changedProperties(baseline, probe);
                if (changed.isEmpty()) {
                    fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.PROBE_NO_OP,
                            expected.objectClass().getName());
                    return java.util.Optional.empty();
                }
                if (changed.size() != 1) {
                    fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.PROBE_AMBIGUOUS,
                            changed.toString());
                    return java.util.Optional.empty();
                }
                RuntimeStructuredRowEvidence evidence = RuntimeStructuredRowEvidence.observe(
                        probe, changed.get(0), runtimeContext).orElse(null);
                if (evidence == null) {
                    fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.ROW_RECONSTRUCTION,
                            changed.get(0) + ":unobservable");
                    return java.util.Optional.empty();
                }
                if (!expected.objectClass().getName().equals(evidence.rowType())) {
                    fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.ROW_RECONSTRUCTION,
                            changed.get(0) + ":" + evidence.rowType());
                    return java.util.Optional.empty();
                }
                if (!evidence.containsScalar(probeResult.identifyingSentinel)) {
                    fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.ROW_RECONSTRUCTION,
                            changed.get(0) + ":sentinel-lost");
                    return java.util.Optional.empty();
                }
                for (ScalarSetterObservation setter : probeResult.setters) {
                    if (!probeResult.uniquelyCoupled(setter)) continue;
                    evidence = evidence.withScalarSetter(
                            setter.canonicalProperty, setter.setterName,
                            setter.inputType, setter.footprint);
                }
                return java.util.Optional.of(new LocalJMeterGuiSemanticMetadata.StructuredRowConsumer(
                        changed.get(0), evidence.rowType(), evidence.rowProperties(),
                        evidence.reconstructionShape(), evidence));
            } finally {
                release(probeGui);
            }
        } catch (ReflectiveOperationException exception) {
            fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.ROW_CONSTRUCTOR,
                    expected.objectClass().getName());
            return java.util.Optional.empty();
        } catch (IOException | RuntimeException | LinkageError exception) {
            fail(failures, LocalJMeterGuiSemanticMetadata.FailureReason.ROW_RECONSTRUCTION,
                    expected.objectClass().getName());
            return java.util.Optional.empty();
        }
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

    private static List<LocalJMeterGuiSemanticTraversal.TableCandidate> matchingCandidates(
            List<LocalJMeterGuiSemanticTraversal.TableCandidate> candidates,
            LocalJMeterGuiSemanticTraversal.TableCandidate expected) {
        ArrayList<LocalJMeterGuiSemanticTraversal.TableCandidate> matches =
                new ArrayList<LocalJMeterGuiSemanticTraversal.TableCandidate>();
        for (LocalJMeterGuiSemanticTraversal.TableCandidate candidate : candidates) {
            if (expected.sameSignature(candidate)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static ScalarProbe addVerifiedSentinel(
            LocalJMeterGuiSemanticTraversal.TableCandidate candidate)
            throws ReflectiveOperationException {
        Constructor<?> constructor = candidate.objectClass().getDeclaredConstructor();
        if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
        }
        Object row = constructor.newInstance();
        if (!(row instanceof TestElement)) return null;
        candidate.model().addRow(row);
        Object identifyingSentinel = null;
        ArrayList<ScalarSetterObservation> setters = new ArrayList<ScalarSetterObservation>();
        for (int column = 0; column < candidate.columnTypes().size(); column++) {
            Object current = candidate.model().getValueAt(0, column);
            Object sentinel = sentinel(candidate.columnTypes().get(column), current);
            if (sentinel == null) {
                return null;
            }
            Object measurement = constructor.newInstance();
            if (!(measurement instanceof TestElement)) return null;
            int measurementRow = candidate.model().getRowCount();
            candidate.model().addRow(measurement);
            List<String> footprint;
            try {
                Map<String, Object> before = primitiveSnapshot((TestElement) measurement);
                candidate.model().setValueAt(sentinel, measurementRow, column);
                footprint = changedPrimitive(
                        before, primitiveSnapshot((TestElement) measurement));
            } finally {
                candidate.model().removeRow(measurementRow);
            }
            candidate.model().setValueAt(sentinel, 0, column);
            if (!footprint.isEmpty()) {
                setters.add(new ScalarSetterObservation(
                        footprint.get(0), candidate.setterName(column),
                        candidate.columnTypes().get(column), footprint));
            }
            if (sentinel.equals(candidate.model().getValueAt(0, column))
                    && identifyingSentinel == null && !(sentinel instanceof Boolean)) {
                identifyingSentinel = sentinel;
            }
        }
        return identifyingSentinel == null
                ? null : new ScalarProbe(identifyingSentinel, setters);
    }

    private static Map<String, Object> primitiveSnapshot(TestElement row) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        org.apache.jmeter.testelement.property.PropertyIterator iterator = row.propertyIterator();
        int count = 0;
        while (iterator.hasNext()
                && count++ < LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3.maxOutputRows) {
            JMeterProperty property = iterator.next();
            Object value = property.getObjectValue();
            if (value == null || value instanceof String
                    || value instanceof Boolean || value instanceof Number) {
                values.put(property.getName(), value);
            }
        }
        return values;
    }

    private static List<String> changedPrimitive(
            Map<String, Object> before, Map<String, Object> after) {
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        names.addAll(before.keySet());
        names.addAll(after.keySet());
        ArrayList<String> changed = new ArrayList<String>();
        for (String name : names) {
            if (!java.util.Objects.equals(before.get(name), after.get(name))) {
                changed.add(name);
            }
        }
        return changed;
    }

    private static Object sentinel(Class<?> type, Object current) {
        long value = NEXT_SENTINEL.incrementAndGet();
        if (String.class.equals(type)) {
            return "__j4a_gui_semantic_" + value + "__";
        }
        if (Integer.class.equals(type) || Integer.TYPE.equals(type)) {
            return Integer.valueOf((int) (1000000000L + value));
        }
        if (Long.class.equals(type) || Long.TYPE.equals(type)) {
            return Long.valueOf(4000000000L + value);
        }
        if (Boolean.class.equals(type) || Boolean.TYPE.equals(type)) {
            return Boolean.valueOf(!Boolean.TRUE.equals(current));
        }
        return null;
    }

    private static List<String> changedProperties(TestElement baseline, TestElement probe) throws IOException {
        Map<String, byte[]> before = snapshots(baseline);
        Map<String, byte[]> after = snapshots(probe);
        Set<String> names = new LinkedHashSet<String>();
        names.addAll(before.keySet());
        names.addAll(after.keySet());
        ArrayList<String> changed = new ArrayList<String>();
        for (String name : names) {
            if (!Arrays.equals(before.get(name), after.get(name))) {
                changed.add(name);
            }
        }
        return changed;
    }

    private static Map<String, byte[]> snapshots(TestElement element) throws IOException {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        org.apache.jmeter.testelement.property.PropertyIterator iterator = element.propertyIterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveService.saveElement(property, output);
            result.put(property.getName(), output.toByteArray());
        }
        return result;
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

    private static void fail(
            List<LocalJMeterGuiSemanticMetadata.Failure> failures,
            LocalJMeterGuiSemanticMetadata.FailureReason reason,
            String detail) {
        failures.add(new LocalJMeterGuiSemanticMetadata.Failure(reason, detail));
    }

    private static final class ScalarProbe {
        private final Object identifyingSentinel;
        private final List<ScalarSetterObservation> setters;

        private ScalarProbe(Object identifyingSentinel, List<ScalarSetterObservation> setters) {
            this.identifyingSentinel = identifyingSentinel;
            this.setters = setters;
        }

        private boolean uniquelyCoupled(ScalarSetterObservation candidate) {
            if (candidate.footprint.size() < 2) return false;
            for (ScalarSetterObservation other : setters) {
                if (other == candidate || other.footprint.size() < 2) continue;
                for (String property : candidate.footprint) {
                    if (other.footprint.contains(property)) return false;
                }
            }
            return true;
        }
    }

    private static final class ScalarSetterObservation {
        private final String canonicalProperty;
        private final String setterName;
        private final Class<?> inputType;
        private final List<String> footprint;

        private ScalarSetterObservation(
                String canonicalProperty,
                String setterName,
                Class<?> inputType,
                List<String> footprint) {
            this.canonicalProperty = canonicalProperty;
            this.setterName = setterName;
            this.inputType = inputType;
            this.footprint = footprint;
        }
    }
}
