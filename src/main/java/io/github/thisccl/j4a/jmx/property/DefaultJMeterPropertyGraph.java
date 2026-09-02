package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyAddressDocument;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

public final class DefaultJMeterPropertyGraph {
    private final PropertyGraphDiscovery discovery = new PropertyGraphDiscovery();

    public GraphSnapshot inspect(TestElement testElement, RuntimeContext runtimeContext) {
        return discovery.inspect(testElement, runtimeContext);
    }

    public MutationReceipt apply(
            TestElement testElement, GraphSnapshot snapshot, List<PropertyWrite> writes) {
        TestElement target = Objects.requireNonNull(testElement, "test element is required");
        GraphSnapshot observed = Objects.requireNonNull(snapshot, "graph snapshot is required");
        List<PropertyWrite> requested = MutationReceipt.immutableWrites(writes);
        List<PreparedWrite> prepared = prepare(target, observed, requested);
        List<JMeterProperty> original = clonedProperties(target);
        try {
            for (PreparedWrite write : prepared) {
                write.attach(target);
            }
            List<PropertyWrite> projectionRequests = new ArrayList<PropertyWrite>(requested);
            projectionRequests.addAll(collectionRemovalProjections(target, observed, requested));
            List<PropertyWrite> receiptValues = projectValues(
                    target, observed.runtimeContext(), projectionRequests, false);
            List<MutationReceipt.RequiredContainer> requiredContainers =
                    requiredContainers(target, requested);
            return new MutationReceipt(
                    observed.runtimeContext(), receiptValues, requiredContainers);
        } catch (RuntimeException exception) {
            setProperties(target, original);
            throw exception;
        }
    }

    public VerificationProjection project(TestElement testElement, MutationReceipt receipt) {
        TestElement target = Objects.requireNonNull(testElement, "test element is required");
        MutationReceipt expected = Objects.requireNonNull(receipt, "mutation receipt is required");
        verifyRequiredContainers(target, expected.requiredContainers());
        List<PropertyWrite> actual = projectValues(
                target, expected.runtimeContext(), expected.writes(), true);
        return new VerificationProjection(expected.runtimeContext(), actual);
    }

    private static List<PreparedWrite> prepare(
            TestElement target, GraphSnapshot snapshot, List<PropertyWrite> writes) {
        Set<PropertyPath> paths = new HashSet<PropertyPath>();
        List<ResolvedWrite> resolved = new ArrayList<ResolvedWrite>(writes.size());
        for (PropertyWrite write : writes) {
            String display = display(write.property());
            if (!paths.add(write.property())) {
                throw new IllegalArgumentException("duplicate property write: " + display);
            }
            rejectOverlappingPath(paths, write.property(), display);
            GraphNode node = snapshot.resolveWritable(write.property());
            requireCurrentAncestors(target, snapshot, write.property());
            Binding binding = resolve(target, write.property());
            if (isEnabled(binding)) {
                requireCurrentEnabledSnapshot(display, binding.owner, node.value());
            } else {
                requireCurrentSnapshot(
                        display, binding.property, node.value(), snapshot.runtimeContext());
            }
            requireWriteShape(display, node, write, binding.property);
            resolved.add(new ResolvedWrite(write, binding));
        }

        List<PreparedWrite> prepared = new ArrayList<PreparedWrite>(resolved.size());
        for (ResolvedWrite write : resolved) {
            prepared.add(isEnabled(write.binding)
                    ? PreparedWrite.enabled(write.write.property(), write.binding.owner,
                            requireEnabledValue(write.write))
                    : new PreparedWrite(write.write.property(), materialize(
                            write.binding, write.write, snapshot.runtimeContext())));
        }
        return prepared;
    }

    private static void requireCurrentEnabledSnapshot(
            String canonical, TestElement owner, RecursiveValue expected) {
        if (!expected.equals(enabledValue(owner))) {
            throw new IllegalArgumentException("stale graph snapshot at '" + canonical + "'");
        }
    }

    private static boolean requireEnabledValue(PropertyWrite write) {
        if (write.type() != GraphType.BOOLEAN
                || write.value().presence() != GraphPresence.PRESENT) {
            throw new IllegalArgumentException(
                    "Property '" + display(write.property()) + "' requires a boolean value");
        }
        return ((Boolean) write.value().scalarValue()).booleanValue();
    }

    private static void requireCurrentAncestors(
            TestElement target, GraphSnapshot snapshot, PropertyPath path) {
        List<PropertyPathSegment> segments = path.segments();
        for (int length = 1; length < segments.size(); length++) {
            PropertyPath ancestor = new PropertyPath(segments.subList(0, length));
            Optional<GraphNode> expected = snapshot.find(ancestor);
            if (expected.isPresent()) {
                Binding current = resolve(target, ancestor);
                requireCurrentSnapshot(
                        display(ancestor),
                        current.property,
                        expected.get().value(),
                        snapshot.runtimeContext());
            }
        }
    }

    private static void rejectOverlappingPath(
            Set<PropertyPath> paths, PropertyPath path, String display) {
        List<PropertyPathSegment> segments = path.segments();
        for (int length = 1; length < segments.size(); length++) {
            PropertyPath ancestor = new PropertyPath(segments.subList(0, length));
            if (paths.contains(ancestor)) {
                throw new IllegalArgumentException(
                        "overlapping property writes: " + display(ancestor) + " and " + display);
            }
        }
        for (PropertyPath other : paths) {
            if (other.equals(path)) {
                continue;
            }
            List<PropertyPathSegment> otherSegments = other.segments();
            if (otherSegments.size() > segments.size()
                    && otherSegments.subList(0, segments.size()).equals(segments)) {
                throw new IllegalArgumentException(
                        "overlapping property writes: " + display + " and " + display(other));
            }
        }
    }

    private static void requireCurrentSnapshot(
            String canonical,
            JMeterProperty current,
            RecursiveValue expected,
            RuntimeContext runtimeContext) {
        RecursiveValue actual = current == null
                ? RecursiveValue.absent(expected.type(), expected.propertyClass())
                : canonical(RuntimePropertyValueDiscovery.read(current, runtimeContext));
        if (!canonical(expected).equals(actual)) {
            throw new IllegalArgumentException(
                    "stale graph snapshot at '" + canonical + "'");
        }
    }

    private static void requireWriteShape(
            String canonical, GraphNode node, PropertyWrite write, JMeterProperty current) {
        if (write.type() != GraphType.OPAQUE && write.type() != node.type()) {
            throw new IllegalArgumentException(
                    "Property '" + canonical + "' type does not match observed graph type");
        }
        if (!write.value().propertyClass().equals(node.value().propertyClass())) {
            throw new IllegalArgumentException(
                    "Property '" + canonical + "' class does not match observed graph class");
        }
        if (write.type() == GraphType.OPAQUE && current == null) {
            throw new IllegalArgumentException(
                    "Property '" + canonical + "' opaque value is absent");
        }
    }

    private static Optional<JMeterProperty> materialize(
            Binding binding, PropertyWrite write, RuntimeContext runtimeContext) {
        Optional<JMeterProperty> prepared = write.preparedProperty();
        if (prepared.isPresent()) {
            prepared.get().setName(binding.name);
            return prepared;
        }
        RecursiveValue value = PropertyGraphDocumentMapper.materializationValue(write);
        JMeterProperty observed = binding.property;
        try {
            switch (write.type()) {
                case COLLECTION:
                    CollectionPropertyCodec collection = new CollectionPropertyCodec();
                    return observed == null
                            ? collection.materialize(binding.name, value)
                            : collection.materialize((MultiProperty) observed, value);
                case MAP:
                    MapPropertyCodec map = new MapPropertyCodec();
                    return observed == null
                            ? map.materialize(binding.name, value)
                            : map.materialize((MapProperty) observed, value);
                case ELEMENT:
                    if (!(observed instanceof TestElementProperty)) {
                        throw new IllegalArgumentException(
                                "element mutation requires an observed TestElementProperty");
                    }
                    return new TestElementPropertyCodec().materialize(
                            (TestElementProperty) observed, value);
                case OPAQUE:
                    return Optional.of(new OpaquePropertyCodec(runtimeContext).materialize(
                            binding.owner, binding.name, value.opaqueValue()));
                default:
                    if (!write.type().isScalar()) {
                        throw new IllegalArgumentException(
                                "unsupported graph write type: " + write.type().wireName());
                    }
                    ScalarPropertyCodec scalar = new ScalarPropertyCodec();
                    return observed == null
                            ? scalar.materialize(binding.name, value)
                            : scalar.materialize(observed, value);
            }
        } catch (ClassCastException exception) {
            throw new IllegalArgumentException(
                    "observed property family does not match requested graph type", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "opaque property could not be decoded by SaveService", exception);
        }
    }

    private static List<PropertyWrite> projectValues(
            TestElement target,
            RuntimeContext runtimeContext,
            List<PropertyWrite> expected,
            boolean verify) {
        List<PropertyWrite> values = new ArrayList<PropertyWrite>(expected.size());
        for (PropertyWrite requested : expected) {
            String addressDisplay = display(requested.property());
            RecursiveValue actual = directAbsentProjection(
                    target, requested, expected, runtimeContext);
            if (verify && !requested.value().equals(actual)) {
                throw new IllegalArgumentException(
                        "property graph projection mismatch at '" + addressDisplay
                                + "'. Expected: " + requested.value()
                                + ". Actual: " + actual);
            }
            values.add(new PropertyWrite(requested.property(), actual.type(), actual));
        }
        return values;
    }

    private static RecursiveValue directAbsentProjection(
            TestElement target,
            PropertyWrite requested,
            List<PropertyWrite> expected,
            RuntimeContext runtimeContext) {
        List<PropertyPathSegment> segments = requested.property().segments();
        if (requested.value().presence() != GraphPresence.ABSENT || segments.size() < 2) {
            return readProjection(
                    resolve(target, requested.property()), requested.value(), runtimeContext);
        }
        PropertyPathSegment terminal = segments.get(segments.size() - 1);
        PropertyPath parentPath = new PropertyPath(segments.subList(0, segments.size() - 1));
        JMeterProperty parent = resolve(target, parentPath).property;
        if (parent instanceof MapProperty
                && terminal.kind() == PropertyPathSegment.Kind.KEY) {
            JMeterProperty current = ((MapProperty) parent).get(terminal.name());
            return readProjection(
                    new Binding((MapProperty) parent, terminal.name(), current),
                    requested.value(),
                    runtimeContext);
        }
        if (parent instanceof MultiProperty
                && terminal.kind() == PropertyPathSegment.Kind.INDEX) {
            if (containsProjection(expected, parentPath)) {
                return requested.value();
            }
            JMeterProperty current = itemAtOrNull((MultiProperty) parent, terminal.index());
            if (current == null) {
                return requested.value();
            }
            return readProjection(
                    new Binding((MultiProperty) parent, terminal.index(), current),
                    requested.value(),
                    runtimeContext);
        }
        return readProjection(
                resolve(target, requested.property()), requested.value(), runtimeContext);
    }

    private static boolean containsProjection(List<PropertyWrite> writes, PropertyPath path) {
        for (PropertyWrite write : writes) {
            if (write.property().segments().equals(path.segments())) {
                return true;
            }
        }
        return false;
    }

    private static List<PropertyWrite> collectionRemovalProjections(
            TestElement target,
            GraphSnapshot snapshot,
            List<PropertyWrite> requested) {
        List<PropertyWrite> values = new ArrayList<PropertyWrite>();
        Set<PropertyPath> captured = new HashSet<PropertyPath>();
        for (PropertyWrite removal : requested) {
            List<PropertyPathSegment> segments = removal.property().segments();
            if (removal.value().presence() != GraphPresence.ABSENT || segments.size() < 2
                    || segments.get(segments.size() - 1).kind()
                            != PropertyPathSegment.Kind.INDEX) {
                continue;
            }
            PropertyPath parentPath = new PropertyPath(
                    segments.subList(0, segments.size() - 1));
            String canonicalParent = display(parentPath);
            if (!captured.add(parentPath)) {
                continue;
            }
            RecursiveValue observed = snapshot.resolveWritable(parentPath).value();
            List<RecursiveValue> items = new ArrayList<RecursiveValue>(observed.items());
            for (PropertyWrite write : requested) {
                List<PropertyPathSegment> writeSegments = write.property().segments();
                if (writeSegments.size() != segments.size()
                        || !writeSegments.subList(0, writeSegments.size() - 1)
                                .equals(parentPath.segments())) {
                    continue;
                }
                PropertyPathSegment terminal = writeSegments.get(writeSegments.size() - 1);
                if (terminal.kind() != PropertyPathSegment.Kind.INDEX) {
                    continue;
                }
                if (write.value().presence() == GraphPresence.ABSENT) {
                    items.remove(terminal.index());
                } else {
                    items.set(terminal.index(), canonical(write.value()));
                }
            }
            RecursiveValue expected = RecursiveValue.collection(
                    observed.propertyClass(), items);
            RecursiveValue actual = readProjection(
                    resolve(target, parentPath), expected, snapshot.runtimeContext());
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "property graph projection mismatch at '" + canonicalParent
                                + "'. Expected: " + expected + ". Actual: " + actual);
            }
            values.add(new PropertyWrite(parentPath, GraphType.COLLECTION, expected));
        }
        return values;
    }

    private static List<MutationReceipt.RequiredContainer> requiredContainers(
            TestElement target, List<PropertyWrite> writes) {
        Set<PropertyPath> captured = new HashSet<PropertyPath>();
        List<MutationReceipt.RequiredContainer> containers =
                new ArrayList<MutationReceipt.RequiredContainer>();
        for (PropertyWrite write : writes) {
            List<PropertyPathSegment> segments = write.property().segments();
            for (int length = 1; length < segments.size(); length++) {
                PropertyPath path = new PropertyPath(segments.subList(0, length));
                if (!captured.add(path)) {
                    continue;
                }
                Binding binding = resolve(target, path);
                if (!(binding.property instanceof TestElementProperty)
                        || !(((TestElementProperty) binding.property).getObjectValue()
                                instanceof TestElement)) {
                    continue;
                }
                TestElement element = ((TestElementProperty) binding.property).getElement();
                containers.add(new MutationReceipt.RequiredContainer(
                        path,
                        GraphPresence.PRESENT,
                        binding.property.getClass().getName(),
                        element.getClass().getName()));
            }
        }
        return containers;
    }

    private static void verifyRequiredContainers(
            TestElement target, List<MutationReceipt.RequiredContainer> requiredContainers) {
        for (MutationReceipt.RequiredContainer expected : requiredContainers) {
            String canonical = display(expected.path());
            Binding binding = resolve(target, expected.path());
            if (expected.presence() != GraphPresence.PRESENT
                    || !(binding.property instanceof TestElementProperty)
                    || !binding.property.getClass().getName().equals(expected.propertyClass())
                    || !(((TestElementProperty) binding.property).getObjectValue()
                            instanceof TestElement)
                    || !((TestElementProperty) binding.property).getElement().getClass().getName()
                            .equals(expected.elementClass())) {
                throw new IllegalArgumentException(
                        "property graph projection mismatch at '" + canonical + "'");
            }
        }
    }

    private static RecursiveValue readProjection(
            Binding binding, RecursiveValue expected, RuntimeContext runtimeContext) {
        if (isEnabled(binding)) {
            return enabledValue(binding.owner);
        }
        if (binding.property == null) {
            return RecursiveValue.absent(expected.type(), expected.propertyClass());
        }
        if (expected.type() == GraphType.OPAQUE) {
            try {
                RecursiveValue.OpaqueValue opaque = new OpaquePropertyCodec(runtimeContext)
                        .read(binding.property);
                return RecursiveValue.opaque(binding.property.getClass().getName(), opaque);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "opaque projection could not be read by SaveService", exception);
            }
        }
        return canonical(RuntimePropertyValueDiscovery.read(binding.property));
    }

    private static boolean isEnabled(Binding binding) {
        return binding.owner != null && TestElement.ENABLED.equals(binding.name);
    }

    private static RecursiveValue enabledValue(TestElement element) {
        return RecursiveValue.scalar(
                GraphType.BOOLEAN,
                BooleanProperty.class.getName(),
                Boolean.valueOf(element.isEnabled()));
    }

    private static RecursiveValue canonical(RecursiveValue value) {
        if (value.presence() == GraphPresence.ABSENT || value.type().isScalar()
                || value.type() == GraphType.OPAQUE) {
            return value;
        }
        if (value.type() == GraphType.COLLECTION) {
            List<RecursiveValue> items = new ArrayList<RecursiveValue>(value.items().size());
            for (RecursiveValue item : value.items()) {
                items.add(canonical(item));
            }
            return RecursiveValue.collection(value.propertyClass(), items);
        }
        if (value.type() == GraphType.MAP) {
            List<RecursiveValue.MapEntry> entries =
                    new ArrayList<RecursiveValue.MapEntry>(value.entries().size());
            for (RecursiveValue.MapEntry entry : value.entries()) {
                entries.add(new RecursiveValue.MapEntry(entry.key(), canonical(entry.value())));
            }
            return RecursiveValue.map(value.propertyClass(), entries);
        }
        List<PropertyWrite> properties = new ArrayList<PropertyWrite>(value.properties().size());
        for (PropertyWrite property : value.properties()) {
            RecursiveValue child = canonical(property.value());
            properties.add(new PropertyWrite(property.property(), child.type(), child));
        }
        Collections.sort(properties, new Comparator<PropertyWrite>() {
            @Override
            public int compare(PropertyWrite left, PropertyWrite right) {
                return display(left.property()).compareTo(display(right.property()));
            }
        });
        return RecursiveValue.element(value.propertyClass(), value.elementClass(), properties);
    }

    private static Binding resolve(TestElement root, PropertyPath path) {
        Object cursor = root;
        List<PropertyPathSegment> segments = path.segments();
        for (int index = 0; index < segments.size(); index++) {
            PropertyPathSegment segment = segments.get(index);
            if (cursor instanceof TestElementProperty) {
                Object value = ((TestElementProperty) cursor).getObjectValue();
                if (!(value instanceof TestElement)) {
                    throw traversalError(path, index);
                }
                cursor = value;
            }
            if (cursor instanceof TestElement) {
                if (segment.kind() != PropertyPathSegment.Kind.PROPERTY) {
                    throw traversalError(path, index);
                }
                TestElement owner = (TestElement) cursor;
                JMeterProperty property = owner.getPropertyOrNull(segment.name());
                if (index == segments.size() - 1) {
                    return new Binding(owner, segment.name(), property);
                }
                if (property == null) {
                    throw traversalError(path, index);
                }
                cursor = property;
                continue;
            }
            JMeterProperty property;
            if (cursor instanceof MapProperty) {
                if (segment.kind() != PropertyPathSegment.Kind.KEY) {
                    throw traversalError(path, index);
                }
                property = ((MapProperty) cursor).get(segment.name());
            } else if (cursor instanceof MultiProperty) {
                if (segment.kind() != PropertyPathSegment.Kind.INDEX) {
                    throw traversalError(path, index);
                }
                property = itemAt((MultiProperty) cursor, segment.index());
            } else {
                throw traversalError(path, index);
            }
            if (property == null) {
                throw traversalError(path, index);
            }
            if (index == segments.size() - 1) {
                return cursor instanceof MapProperty
                        ? new Binding((MapProperty) cursor, segment.name(), property)
                        : new Binding((MultiProperty) cursor, segment.index(), property);
            }
            cursor = property;
        }
        throw new IllegalArgumentException("property path is required");
    }

    private static JMeterProperty itemAt(MultiProperty property, int requestedIndex) {
        JMeterProperty item = itemAtOrNull(property, requestedIndex);
        if (item != null) {
            return item;
        }
        throw new IllegalArgumentException(
                "collection index is out of bounds: " + requestedIndex);
    }

    private static JMeterProperty itemAtOrNull(MultiProperty property, int requestedIndex) {
        int index = 0;
        PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            JMeterProperty item = iterator.next();
            if (index == requestedIndex) {
                return item;
            }
            index++;
        }
        return null;
    }

    private static IllegalArgumentException traversalError(PropertyPath path, int index) {
        return new IllegalArgumentException(
                "property path cannot traverse '"
                        + display(new PropertyPath(
                                path.segments().subList(0, index + 1))) + "'");
    }

    private static String display(PropertyPath path) {
        return io.github.thisccl.j4a.path.PropertyAddress.fromPath(path).toString();
    }

    private static List<JMeterProperty> clonedProperties(TestElement element) {
        List<JMeterProperty> properties = new ArrayList<JMeterProperty>();
        PropertyIterator iterator = element.propertyIterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            JMeterProperty clone = property.clone();
            if (clone == property && !(property instanceof NullProperty)
                    || !clone.getClass().equals(property.getClass())) {
                throw new IllegalArgumentException(
                        "candidate property cannot be cloned safely: " + property.getName());
            }
            properties.add(clone);
        }
        return properties;
    }

    private static void setProperties(TestElement target, List<JMeterProperty> properties) {
        target.clear();
        for (JMeterProperty property : properties) {
            target.setProperty(property);
        }
    }

    private static final class ResolvedWrite {
        private final PropertyWrite write;
        private final Binding binding;

        private ResolvedWrite(PropertyWrite write, Binding binding) {
            this.write = write;
            this.binding = binding;
        }
    }

    private static final class PreparedWrite {
        private final PropertyPath path;
        private final Optional<JMeterProperty> property;
        private final TestElement enabledOwner;
        private final boolean enabledValue;

        private PreparedWrite(PropertyPath path, Optional<JMeterProperty> property) {
            this.path = path;
            this.property = property;
            this.enabledOwner = null;
            this.enabledValue = false;
        }

        private PreparedWrite(PropertyPath path, TestElement enabledOwner, boolean enabledValue) {
            this.path = path;
            this.property = Optional.empty();
            this.enabledOwner = enabledOwner;
            this.enabledValue = enabledValue;
        }

        private static PreparedWrite enabled(
                PropertyPath path, TestElement owner, boolean value) {
            return new PreparedWrite(path, owner, value);
        }

        private void attach(TestElement root) {
            if (enabledOwner != null) {
                enabledOwner.setEnabled(enabledValue);
                return;
            }
            if (path.segments().size() > 1) {
                PropertyPathSegment first = path.segments().get(0);
                if (first.kind() != PropertyPathSegment.Kind.PROPERTY) {
                    throw new IllegalArgumentException("graph path must start with a property");
                }
                JMeterProperty ancestor = root.getPropertyOrNull(first.name());
                if (ancestor == null) {
                    throw traversalError(path, 0);
                }
                Optional<JMeterProperty> rebuilt = rebuildAncestor(
                        ancestor, path.segments(), 1, property, path);
                if (rebuilt.isPresent()) {
                    root.setProperty(rebuilt.get());
                } else {
                    root.removeProperty(first.name());
                }
                return;
            }
            Binding binding = resolve(root, path);
            if (binding.owner != null) {
                if (property.isPresent()) {
                    binding.owner.setProperty(property.get());
                } else {
                    binding.owner.removeProperty(binding.name);
                }
                return;
            }
            if (binding.container instanceof MapProperty) {
                Map<String, JMeterProperty> entries = mapEntries((MapProperty) binding.container);
                if (property.isPresent()) {
                    entries.put(binding.name, property.get());
                } else {
                    entries.remove(binding.name);
                }
                return;
            }
            if (binding.container instanceof CollectionProperty) {
                CollectionProperty collection = (CollectionProperty) binding.container;
                if (property.isPresent()) {
                    collection.set(binding.index, property.get());
                } else {
                    collection.remove(binding.index);
                }
                return;
            }
            throw new IllegalArgumentException("graph leaf container is not writable");
        }

        private static Optional<JMeterProperty> rebuildAncestor(
                JMeterProperty observed,
                List<PropertyPathSegment> segments,
                int index,
                Optional<JMeterProperty> replacement,
                PropertyPath path) {
            JMeterProperty copy = cloneProperty(observed);
            PropertyPathSegment segment = segments.get(index);
            boolean terminal = index == segments.size() - 1;
            if (copy instanceof TestElementProperty) {
                if (segment.kind() != PropertyPathSegment.Kind.PROPERTY) {
                    throw traversalError(path, index);
                }
                TestElement element = detachedElement(
                        (TestElementProperty) observed, (TestElementProperty) copy);
                Optional<JMeterProperty> child = terminal
                        ? replacement
                        : rebuildAncestor(requiredChild(
                                element.getPropertyOrNull(segment.name()), path, index),
                                segments, index + 1, replacement, path);
                if (child.isPresent()) {
                    element.setProperty(child.get());
                } else {
                    element.removeProperty(segment.name());
                }
                ((TestElementProperty) copy).setElement(element);
                return Optional.of(copy);
            }
            if (copy instanceof MapProperty) {
                if (segment.kind() != PropertyPathSegment.Kind.KEY) {
                    throw traversalError(path, index);
                }
                Map<String, JMeterProperty> entries = mapEntries((MapProperty) copy);
                Optional<JMeterProperty> child = terminal
                        ? replacement
                        : rebuildAncestor(requiredChild(
                                entries.get(segment.name()), path, index),
                                segments, index + 1, replacement, path);
                if (child.isPresent()) {
                    entries.put(segment.name(), child.get());
                } else {
                    entries.remove(segment.name());
                }
                return Optional.of(copy);
            }
            if (copy instanceof CollectionProperty) {
                if (segment.kind() != PropertyPathSegment.Kind.INDEX) {
                    throw traversalError(path, index);
                }
                CollectionProperty collection = (CollectionProperty) copy;
                Optional<JMeterProperty> child = terminal
                        ? replacement
                        : rebuildAncestor(itemAt(collection, segment.index()),
                                segments, index + 1, replacement, path);
                if (child.isPresent()) {
                    collection.set(segment.index(), child.get());
                } else {
                    collection.remove(segment.index());
                }
                return Optional.of(copy);
            }
            throw traversalError(path, index);
        }

        private static TestElement detachedElement(
                TestElementProperty observed, TestElementProperty copy) {
            TestElement source = observed.getElement();
            TestElement element = copy.getElement();
            if (element == source) {
                Object cloned = source.clone();
                if (cloned == source || !(cloned instanceof TestElement)
                        || !cloned.getClass().equals(source.getClass())) {
                    throw new IllegalArgumentException(
                            "candidate element cannot be cloned safely: " + observed.getName());
                }
                element = (TestElement) cloned;
            }
            return element;
        }

        private static JMeterProperty requiredChild(
                JMeterProperty property, PropertyPath path, int index) {
            if (property != null) {
                return property;
            }
            throw traversalError(path, index);
        }

        private static JMeterProperty cloneProperty(JMeterProperty property) {
            JMeterProperty copy = property.clone();
            if (copy == property && !(property instanceof NullProperty)
                    || !copy.getClass().equals(property.getClass())) {
                throw new IllegalArgumentException(
                        "candidate property cannot be cloned safely: " + property.getName());
            }
            return copy;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, JMeterProperty> mapEntries(MapProperty property) {
            return (Map<String, JMeterProperty>) property.getObjectValue();
        }
    }

    private static final class Binding {
        private final TestElement owner;
        private final MultiProperty container;
        private final int index;
        private final String name;
        private final JMeterProperty property;

        private Binding(TestElement owner, String name, JMeterProperty property) {
            this.owner = owner;
            this.container = null;
            this.index = -1;
            this.name = name;
            this.property = property;
        }

        private Binding(MapProperty owner, String name, JMeterProperty property) {
            this.owner = null;
            this.container = owner;
            this.index = -1;
            this.name = name;
            this.property = property;
        }

        private Binding(MultiProperty owner, int index, JMeterProperty property) {
            this.owner = null;
            this.container = owner;
            this.index = index;
            this.name = property.getName();
            this.property = property;
        }
    }
}
