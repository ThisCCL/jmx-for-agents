package io.github.thisccl.j4a.jmx.property;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

final class RuntimeEmptyDirectRowProof {
    private static final int CANDIDATE_LIMIT = 32;

    private RuntimeEmptyDirectRowProof() {
    }

    static Optional<Evidence> observe(TestElement owner, MultiProperty selected) {
        if (owner.getPropertyOrNull(selected.getName()) != selected || !rows(selected).isEmpty()) {
            return Optional.empty();
        }
        ArrayList<Method> candidates = candidates(owner.getClass());
        if (candidates.size() > CANDIDATE_LIMIT) {
            return Optional.empty();
        }
        Evidence accepted = null;
        for (int index = 0; index < candidates.size(); index++) {
            Evidence evidence = exercise(owner, selected, candidates.get(index)).orElse(null);
            if (evidence != null) {
                if (accepted != null) {
                    return Optional.empty();
                }
                accepted = evidence;
            }
        }
        return Optional.ofNullable(accepted);
    }

    private static ArrayList<Method> candidates(Class<?> ownerClass) {
        ArrayList<Method> candidates = new ArrayList<Method>();
        for (Method method : ownerClass.getMethods()) {
            if (eligible(method)) {
                candidates.add(method);
            }
        }
        Collections.sort(candidates, Comparator.comparing(RuntimeEmptyDirectRowProof::signature));
        return candidates;
    }

    private static boolean eligible(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || method.getParameterTypes().length != 1) {
            return false;
        }
        Class<?> rowClass = method.getParameterTypes()[0];
        return TestElement.class.isAssignableFrom(rowClass)
                && !rowClass.isInterface()
                && !Modifier.isAbstract(rowClass.getModifiers())
                && publicConstructor(rowClass).isPresent();
    }

    private static Optional<Evidence> exercise(
            TestElement owner, MultiProperty selected, Method mutator) {
        try {
            Class<?> rowClass = mutator.getParameterTypes()[0];
            Constructor<?> constructor = publicConstructor(rowClass).get();
            TestElement typedClone = cloneOwner(owner);
            Map<String, byte[]> before = snapshots(typedClone);
            TestElement constructed = (TestElement) constructor.newInstance();
            mutator.invoke(typedClone, constructed);
            CandidateObservation typed = observeChange(
                    typedClone, selected.getName(), before, rowClass).orElse(null);
            if (typed == null) {
                return Optional.empty();
            }
            CandidateObservation pristine = creator(mutator).isPresent()
                    ? exerciseCreator(owner, selected.getName(), mutator, rowClass).orElse(null)
                    : typed;
            if (pristine == null || !sameSchema(typed.fields, pristine.fields)) {
                return Optional.empty();
            }
            CandidateObservation reloaded = reload(
                    typedClone.getPropertyOrNull(selected.getName()), rowClass)
                    .orElse(null);
            if (reloaded == null || !sameSchema(typed.fields, reloaded.fields)) {
                return Optional.empty();
            }
            Optional<StructuredRowReconstruction> reconstruction =
                    RuntimeRowMetadataProof.directReconstruction(rowClass, pristine.wrapper);
            return reconstruction.isPresent()
                    ? Optional.of(new Evidence(
                            rowClass, pristine.wrapper.getClass().getName(), pristine.fields,
                            reconstruction.get()))
                    : Optional.<Evidence>empty();
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private static Optional<CandidateObservation> exerciseCreator(
            TestElement owner, String selectedName, Method mutator, Class<?> rowClass)
            throws IOException, ReflectiveOperationException {
        Method creator = creator(mutator).get();
        TestElement creatorClone = cloneOwner(owner);
        Map<String, byte[]> before = snapshots(creatorClone);
        creator.invoke(creatorClone);
        return observeChange(creatorClone, selectedName, before, rowClass);
    }

    private static Optional<Method> creator(Method mutator) {
        try {
            Method method = mutator.getDeclaringClass().getMethod(mutator.getName());
            return Modifier.isPublic(method.getModifiers())
                    ? Optional.of(method) : Optional.<Method>empty();
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
    }

    private static Optional<CandidateObservation> observeChange(
            TestElement owner, String selectedName, Map<String, byte[]> before, Class<?> rowClass)
            throws IOException {
        Map<String, byte[]> after = snapshots(owner);
        if (!before.keySet().equals(after.keySet())) {
            return Optional.empty();
        }
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            boolean changed = !Arrays.equals(entry.getValue(), after.get(entry.getKey()));
            if (changed != entry.getKey().equals(selectedName)) {
                return Optional.empty();
            }
        }
        JMeterProperty selected = owner.getPropertyOrNull(selectedName);
        if (!(selected instanceof MultiProperty)) {
            return Optional.empty();
        }
        List<JMeterProperty> rows = rows((MultiProperty) selected);
        if (rows.size() != 1 || !(rows.get(0).getObjectValue() instanceof TestElement)
                || rows.get(0).getObjectValue().getClass() != rowClass) {
            return Optional.empty();
        }
        List<StructuredRowField> fields = pristineFields((TestElement) rows.get(0).getObjectValue());
        return fields.isEmpty()
                ? Optional.<CandidateObservation>empty()
                : Optional.of(new CandidateObservation(rows.get(0), fields));
    }

    private static Optional<CandidateObservation> reload(
            JMeterProperty selected, Class<?> rowClass) throws IOException {
        Object loaded = OpaqueEnvelope.load(OpaqueEnvelope.save(selected));
        if (loaded == null || loaded.getClass() != selected.getClass()
                || !(loaded instanceof MultiProperty)) {
            return Optional.empty();
        }
        List<JMeterProperty> rows = rows((MultiProperty) loaded);
        if (rows.size() != 1 || !(rows.get(0).getObjectValue() instanceof TestElement)
                || rows.get(0).getObjectValue().getClass() != rowClass) {
            return Optional.empty();
        }
        List<StructuredRowField> fields = pristineFields((TestElement) rows.get(0).getObjectValue());
        return fields.isEmpty()
                ? Optional.<CandidateObservation>empty()
                : Optional.of(new CandidateObservation(rows.get(0), fields));
    }

    private static List<StructuredRowField> pristineFields(TestElement row) {
        return RuntimePristineRowDefaults.prove(row)
                .orElse(Collections.<StructuredRowField>emptyList());
    }

    private static Map<String, byte[]> snapshots(TestElement owner) throws IOException {
        LinkedHashMap<String, byte[]> snapshots = new LinkedHashMap<String, byte[]>();
        PropertyIterator properties = owner.propertyIterator();
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            if (snapshots.put(property.getName(), OpaqueEnvelope.save(property)) != null) {
                throw new IOException("duplicate runtime property name");
            }
        }
        return snapshots;
    }

    private static TestElement cloneOwner(TestElement owner) {
        Object clone = owner.clone();
        if (clone == owner || clone == null || clone.getClass() != owner.getClass()) {
            throw new IllegalArgumentException("runtime row owner cannot be cloned safely");
        }
        return (TestElement) clone;
    }

    private static List<JMeterProperty> rows(MultiProperty property) {
        ArrayList<JMeterProperty> rows = new ArrayList<JMeterProperty>();
        PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            rows.add(iterator.next());
        }
        return rows;
    }

    private static boolean sameSchema(
            List<StructuredRowField> left, List<StructuredRowField> right) {
        return left.equals(right);
    }

    private static Optional<Constructor<?>> publicConstructor(Class<?> rowClass) {
        try {
            return Optional.<Constructor<?>>of(rowClass.getConstructor());
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
    }

    private static String signature(Method method) {
        return method.getName() + "(" + method.getParameterTypes()[0].getName() + ")";
    }

    static final class Evidence {
        private final Class<?> rowClass;
        private final String wrapperClass;
        private final List<StructuredRowField> fields;
        private final StructuredRowReconstruction reconstruction;

        private Evidence(Class<?> rowClass, String wrapperClass,
                List<StructuredRowField> fields, StructuredRowReconstruction reconstruction) {
            this.rowClass = rowClass;
            this.wrapperClass = wrapperClass;
            this.fields = fields;
            this.reconstruction = reconstruction;
        }

        Class<?> rowClass() { return rowClass; }
        String wrapperClass() { return wrapperClass; }
        List<StructuredRowField> fields() { return fields; }
        StructuredRowReconstruction reconstruction() { return reconstruction; }
    }

    private static final class CandidateObservation {
        private final JMeterProperty wrapper;
        private final List<StructuredRowField> fields;

        private CandidateObservation(JMeterProperty wrapper, List<StructuredRowField> fields) {
            this.wrapper = wrapper;
            this.fields = fields;
        }
    }
}
