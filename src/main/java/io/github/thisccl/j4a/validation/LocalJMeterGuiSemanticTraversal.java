package io.github.thisccl.j4a.validation;

import static io.github.thisccl.j4a.validation.LocalJMeterGuiSemanticMetadata.FailureReason;

import java.awt.Window;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.apache.jmeter.gui.Binding;
import org.apache.jmeter.gui.BindingGroup;
import org.apache.jmeter.testelement.schema.BooleanPropertyDescriptor;
import org.apache.jmeter.testelement.schema.DoublePropertyDescriptor;
import org.apache.jmeter.testelement.schema.FloatPropertyDescriptor;
import org.apache.jmeter.testelement.schema.IntegerPropertyDescriptor;
import org.apache.jmeter.testelement.schema.LongPropertyDescriptor;
import org.apache.jmeter.testelement.schema.PropertyDescriptor;
import org.apache.jorphan.gui.ObjectTableModel;
import org.apache.jorphan.reflect.Functor;

final class LocalJMeterGuiSemanticTraversal {
    interface FieldReader {
        Object read(Field field, Object owner) throws IllegalAccessException;
    }

    private static final FieldReader REFLECTIVE_FIELD_READER = new FieldReader() {
        @Override
        public Object read(Field field, Object owner) throws IllegalAccessException {
            if (!field.isAccessible()) {
                field.setAccessible(true);
            }
            return field.get(owner);
        }
    };

    private LocalJMeterGuiSemanticTraversal() {
    }

    static LocalJMeterGuiSemanticMetadata.Observation observe(Object root, String version) {
        return observe(root, version, LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3,
                REFLECTIVE_FIELD_READER, System::nanoTime);
    }

    static LocalJMeterGuiSemanticMetadata.Observation observe(
            Object root,
            String version,
            LocalJMeterGuiSemanticMetadata.Budget budget,
            FieldReader fieldReader,
            LongSupplier nanoTime) {
        return inspect(root, version, budget, fieldReader, nanoTime).observation();
    }

    static TraversalResult inspect(Object root, String version) {
        return inspect(root, version, LocalJMeterGuiSemanticMetadata.Budget.CORE_5_6_3,
                REFLECTIVE_FIELD_READER, System::nanoTime);
    }

    static TraversalResult inspect(
            Object root,
            String version,
            LocalJMeterGuiSemanticMetadata.Budget budget,
            FieldReader fieldReader,
            LongSupplier nanoTime) {
        State state = new State(budget, fieldReader, nanoTime);
        if (!LocalJMeterGuiSemanticMetadata.SUPPORTED_JMETER_VERSION.equals(version)) {
            state.fail(FailureReason.UNSUPPORTED_VERSION, String.valueOf(version));
            return state.result();
        }
        if (root == null) {
            return state.result();
        }
        if (root instanceof Window) {
            state.fail(FailureReason.TOP_LEVEL_WINDOW, root.getClass().getName());
            return state.result();
        }
        ClassLoader jmeterLoader = Binding.class.getClassLoader();
        if (root.getClass().getClassLoader() != jmeterLoader) {
            state.fail(FailureReason.FOREIGN_CLASSLOADER, root.getClass().getName());
            return state.result();
        }
        state.walk(root);
        return state.result();
    }

    private static final class State {
        private final LocalJMeterGuiSemanticMetadata.Budget budget;
        private final FieldReader fieldReader;
        private final LongSupplier nanoTime;
        private final long started;
        private final Deque<Node> pending = new ArrayDeque<>();
        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        private final Map<String, LocalJMeterGuiSemanticMetadata.ScalarDescriptor> descriptors =
                new LinkedHashMap<>();
        private final Set<String> conflictingDescriptors = new LinkedHashSet<>();
        private final List<LocalJMeterGuiSemanticMetadata.Failure> failures = new ArrayList<>();
        private final List<TableCandidate> tables = new ArrayList<TableCandidate>();
        private int fields;
        private int maximumDepth;
        private int descriptorCandidates;
        private int tableCandidates;
        private boolean stopped;
        private Class<?> selectedRootType;

        State(LocalJMeterGuiSemanticMetadata.Budget budget, FieldReader fieldReader, LongSupplier nanoTime) {
            this.budget = budget;
            this.fieldReader = fieldReader;
            this.nanoTime = nanoTime;
            this.started = nanoTime.getAsLong();
        }

        void walk(Object root) {
            selectedRootType = root.getClass();
            pending.add(new Node(root, 0));
            while (!pending.isEmpty() && !stopped) {
                if (elapsedExceeded()) break;
                Node node = pending.removeFirst();
                maximumDepth = Math.max(maximumDepth, node.depth);
                if (node.depth > budget.maxDepth) {
                    stop(FailureReason.DEPTH_BUDGET, String.valueOf(node.depth));
                    break;
                }
                Object value = node.value;
                if (value == null || visited.contains(value)) continue;
                if (visited.size() >= budget.maxObjects) {
                    stop(FailureReason.OBJECT_BUDGET, String.valueOf(visited.size()));
                    break;
                }
                visited.add(value);
                if (value instanceof PropertyDescriptor) {
                    descriptor((PropertyDescriptor<?, ?>) value);
                    continue;
                }
                if (isTableModel(value)) {
                    tableCandidates++;
                    if (tableCandidates > budget.maxTableCandidates) {
                        stop(FailureReason.TABLE_CANDIDATE_BUDGET, String.valueOf(tableCandidates));
                    } else {
                        table((ObjectTableModel) value);
                    }
                    continue;
                }
                if (value instanceof Iterable) {
                    for (Object item : (Iterable<?>) value) enqueue(item, node.depth + 1);
                    continue;
                }
                if (value instanceof Map) {
                    for (Object item : ((Map<?, ?>) value).values()) enqueue(item, node.depth + 1);
                    continue;
                }
                if (value.getClass().isArray()) {
                    for (int index = 0; index < Array.getLength(value); index++) {
                        enqueue(Array.get(value, index), node.depth + 1);
                    }
                    continue;
                }
                if (!traversable(value.getClass())) continue;
                reflect(value, node.depth);
            }
        }

        private void reflect(Object value, int depth) {
            boolean binding = value instanceof Binding && !(value instanceof BindingGroup);
            boolean foundDescriptor = false;
            for (Class<?> type = value.getClass(); traversable(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    fields++;
                    if (fields > budget.maxFields) {
                        stop(FailureReason.FIELD_BUDGET, String.valueOf(fields));
                        return;
                    }
                    Object fieldValue;
                    try {
                        fieldValue = fieldReader.read(field, value);
                    } catch (RuntimeException | IllegalAccessException exception) {
                        fail(FailureReason.INACCESSIBLE_FIELD,
                                type.getName() + "#" + field.getName());
                        continue;
                    }
                    if (fieldValue instanceof PropertyDescriptor) foundDescriptor = true;
                    enqueue(fieldValue, depth + 1);
                }
            }
            if (binding && !foundDescriptor) {
                fail(FailureReason.UNSUPPORTED_BINDING, value.getClass().getName());
            }
        }

        private void descriptor(PropertyDescriptor<?, ?> descriptor) {
            descriptorCandidates++;
            if (descriptorCandidates > budget.maxDescriptors) {
                stop(FailureReason.DESCRIPTOR_BUDGET, String.valueOf(descriptorCandidates));
                return;
            }
            try {
                String name = descriptor.getName();
                String type = scalarType(descriptor);
                if (name == null || name.trim().isEmpty() || type == null) {
                    fail(FailureReason.OPAQUE_DESCRIPTOR, descriptor.getClass().getName());
                    return;
                }
                LocalJMeterGuiSemanticMetadata.ScalarDescriptor candidate =
                        new LocalJMeterGuiSemanticMetadata.ScalarDescriptor(
                                name, type, descriptor.getDefaultValue());
                if (conflictingDescriptors.contains(name)) return;
                LocalJMeterGuiSemanticMetadata.ScalarDescriptor existing = descriptors.get(name);
                if (existing != null && (!existing.type().equals(candidate.type())
                        || !java.util.Objects.equals(existing.defaultValue(), candidate.defaultValue()))) {
                    descriptors.remove(name);
                    conflictingDescriptors.add(name);
                    fail(FailureReason.CONFLICTING_DESCRIPTOR, name);
                    return;
                }
                if (existing == null) {
                    if (descriptors.size() >= budget.maxOutputRows) {
                        stop(FailureReason.OUTPUT_BUDGET, String.valueOf(descriptors.size()));
                        return;
                    }
                    descriptors.put(name, candidate);
                }
            } catch (RuntimeException | LinkageError exception) {
                fail(FailureReason.OPAQUE_DESCRIPTOR, descriptor.getClass().getName());
            }
        }

        private void table(ObjectTableModel model) {
            try {
                Class<?> objectClass = null;
                int classFields = 0;
                for (Field field : ObjectTableModel.class.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && Class.class.equals(field.getType())) {
                        classFields++;
                        Object value = fieldReader.read(field, model);
                        if (value instanceof Class) {
                            objectClass = (Class<?>) value;
                        }
                    }
                }
                if (classFields != 1 || objectClass == null) {
                    fail(FailureReason.TABLE_MODEL_SHAPE, model.getClass().getName());
                    return;
                }
                ArrayList<Class<?>> columns = new ArrayList<Class<?>>();
                ArrayList<String> setters = new ArrayList<String>();
                Field writeFunctorsField = ObjectTableModel.class.getDeclaredField("writeFunctors");
                Object writeFunctorsValue = fieldReader.read(writeFunctorsField, model);
                if (!(writeFunctorsValue instanceof List<?>)) {
                    fail(FailureReason.TABLE_MODEL_SHAPE, model.getClass().getName());
                    return;
                }
                List<?> writeFunctors = (List<?>) writeFunctorsValue;
                Field methodNameField = Functor.class.getDeclaredField("methodName");
                for (int index = 0; index < model.getColumnCount(); index++) {
                    Class<?> column = model.getColumnClass(index);
                    if (column == null || index >= writeFunctors.size()
                            || !(writeFunctors.get(index) instanceof Functor)) {
                        fail(FailureReason.TABLE_MODEL_SHAPE, objectClass.getName());
                        return;
                    }
                    Object methodName = fieldReader.read(methodNameField, writeFunctors.get(index));
                    if (!(methodName instanceof String) || ((String) methodName).isEmpty()) {
                        fail(FailureReason.TABLE_MODEL_SHAPE, objectClass.getName());
                        return;
                    }
                    columns.add(column);
                    setters.add((String) methodName);
                }
                tables.add(new TableCandidate(model, objectClass, columns, setters));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                fail(FailureReason.INACCESSIBLE_FIELD, ObjectTableModel.class.getName());
            }
        }

        private boolean elapsedExceeded() {
            long elapsed = nanoTime.getAsLong() - started;
            if (elapsed > budget.maxElapsedNanos) {
                stop(FailureReason.ELAPSED_TIME_BUDGET, String.valueOf(elapsed));
                return true;
            }
            return false;
        }

        private void enqueue(Object value, int depth) {
            if (value != null && !stopped) pending.addLast(new Node(value, depth));
        }

        void fail(FailureReason reason, String detail) {
            failures.add(new LocalJMeterGuiSemanticMetadata.Failure(reason, detail));
        }

        private void stop(FailureReason reason, String detail) {
            fail(reason, detail);
            stopped = true;
        }

        TraversalResult result() {
            return new TraversalResult(
                    new LocalJMeterGuiSemanticMetadata.Observation(
                            new ArrayList<>(descriptors.values()), failures,
                            new LocalJMeterGuiSemanticMetadata.Stats(
                                    visited.size(), fields, maximumDepth, descriptorCandidates, tableCandidates,
                                    Math.max(0L, nanoTime.getAsLong() - started))),
                    tables);
        }

        private boolean traversable(Class<?> type) {
            if (type == null || Object.class.equals(type)) return false;
            if (type == selectedRootType) return true;
            String name = type.getName();
            return name.startsWith("org.apache.jmeter.")
                    || name.startsWith("io.github.thisccl.j4a.validation.");
        }
    }

    private static boolean isTableModel(Object value) {
        return "org.apache.jorphan.gui.ObjectTableModel".equals(value.getClass().getName());
    }

    private static String scalarType(PropertyDescriptor<?, ?> descriptor) {
        if (descriptor instanceof BooleanPropertyDescriptor) return "boolean";
        if (descriptor instanceof IntegerPropertyDescriptor) return "int";
        if (descriptor instanceof LongPropertyDescriptor) return "long";
        if (descriptor instanceof FloatPropertyDescriptor) return "float";
        if (descriptor instanceof DoublePropertyDescriptor) return "double";
        return descriptor.getClass().getName().endsWith("StringPropertyDescriptor") ? "string" : null;
    }

    private static final class Node {
        private final Object value;
        private final int depth;

        private Node(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    static final class TraversalResult {
        private final LocalJMeterGuiSemanticMetadata.Observation observation;
        private final List<TableCandidate> tables;

        private TraversalResult(
                LocalJMeterGuiSemanticMetadata.Observation observation, List<TableCandidate> tables) {
            this.observation = observation;
            this.tables = Collections.unmodifiableList(new ArrayList<TableCandidate>(tables));
        }

        LocalJMeterGuiSemanticMetadata.Observation observation() {
            return observation;
        }

        List<TableCandidate> tables() {
            return tables;
        }
    }

    static final class TableCandidate {
        private final ObjectTableModel model;
        private final Class<?> objectClass;
        private final List<Class<?>> columnTypes;
        private final List<String> setterNames;

        private TableCandidate(
                ObjectTableModel model,
                Class<?> objectClass,
                List<Class<?>> columnTypes,
                List<String> setterNames) {
            this.model = model;
            this.objectClass = objectClass;
            this.columnTypes = Collections.unmodifiableList(new ArrayList<Class<?>>(columnTypes));
            this.setterNames = Collections.unmodifiableList(new ArrayList<String>(setterNames));
        }

        ObjectTableModel model() { return model; }
        Class<?> objectClass() { return objectClass; }
        List<Class<?>> columnTypes() { return columnTypes; }
        String setterName(int column) { return setterNames.get(column); }

        boolean sameSignature(TableCandidate other) {
            return objectClass == other.objectClass
                    && columnTypes.equals(other.columnTypes)
                    && setterNames.equals(other.setterNames);
        }
    }
}
