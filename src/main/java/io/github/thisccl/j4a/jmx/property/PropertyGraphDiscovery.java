package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class PropertyGraphDiscovery {
    private static final String SYSTEM_REASON =
            "JMeter identity metadata is managed by the selected runtime";
    private static final String CODEC_REASON = "Property write codec is not implemented";

    GraphSnapshot inspect(TestElement testElement, RuntimeContext runtimeContext) {
        Objects.requireNonNull(testElement, "test element is required");
        Objects.requireNonNull(runtimeContext, "runtime context is required");
        Traversal traversal = new Traversal();
        discoverElement(testElement, Collections.<PropertyPathSegment>emptyList(),
                traversal, runtimeContext);
        List<GraphNode> nodes = new ArrayList<GraphNode>(traversal.candidates.size());
        for (Candidate candidate : traversal.candidates.values()) {
            nodes.add(candidate.toNode());
        }
        return new GraphSnapshot(runtimeContext, nodes);
    }

    private void discoverElement(
            TestElement element,
            List<PropertyPathSegment> prefix,
            Traversal traversal,
            RuntimeContext runtimeContext) {
        if (traversal.elementStack.put(element, Boolean.TRUE) != null) {
            return;
        }
        try {
            List<Candidate> level = new ArrayList<Candidate>();
            List<NestedProperty> nested = new ArrayList<NestedProperty>();
            PropertyIterator iterator = element.propertyIterator();
            while (iterator.hasNext()) {
                JMeterProperty property = iterator.next();
                PropertyPath path = path(prefix, property.getName());
                Candidate candidate = Candidate.runtime(path, property, runtimeContext);
                traversal.candidates.put(path, candidate);
                level.add(candidate);
                nested.add(new NestedProperty(path, property));
            }
            for (DeclaredPropertyDiscovery.Declaration declaration
                    : DeclaredPropertyDiscovery.declarations(element)) {
                PropertyPath path = path(prefix, declaration.name());
                Candidate candidate = traversal.candidates.get(path);
                if (candidate != null) {
                    candidate.addSource(declaration.source());
                } else if (declaration.shape() != null) {
                    candidate = Candidate.absent(path, declaration.shape(), declaration.source());
                    traversal.candidates.put(path, candidate);
                    level.add(candidate);
                }
            }
            PropertyPath enabledPath = path(prefix, TestElement.ENABLED);
            traversal.candidates.put(enabledPath, Candidate.enabled(enabledPath, element));
            for (NestedProperty child : nested) {
                discoverNested(child.property, child.path, traversal, runtimeContext);
            }
        } finally {
            traversal.elementStack.remove(element);
        }
    }

    private void discoverNested(
            JMeterProperty property,
            PropertyPath path,
            Traversal traversal,
            RuntimeContext runtimeContext) {
        Object value = property.getObjectValue();
        if (property instanceof TestElementProperty && value instanceof TestElement) {
            discoverElement((TestElement) value, path.segments(), traversal, runtimeContext);
            return;
        }
        if (property instanceof MapProperty) {
            PropertyIterator iterator = ((MapProperty) property).iterator();
            while (iterator.hasNext()) {
                JMeterProperty child = iterator.next();
                PropertyPath childPath = mapChild(path, child.getName());
                if (childPath != null) {
                    discoverScalarLeaf(child, childPath, traversal, runtimeContext);
                    discoverNested(child, childPath, traversal, runtimeContext);
                }
            }
            return;
        }
        if (property instanceof CollectionProperty) {
            int index = 0;
            PropertyIterator iterator = ((MultiProperty) property).iterator();
            while (iterator.hasNext()) {
                JMeterProperty child = iterator.next();
                PropertyPath childPath = indexedChild(path, index);
                discoverScalarLeaf(child, childPath, traversal, runtimeContext);
                discoverNested(child, childPath, traversal, runtimeContext);
                index++;
            }
        }
    }

    private static void discoverScalarLeaf(
            JMeterProperty property,
            PropertyPath path,
            Traversal traversal,
            RuntimeContext runtimeContext) {
        Candidate candidate = Candidate.runtime(path, property, runtimeContext);
        if (candidate.binding.value.type().isScalar()) {
            traversal.candidates.put(path, candidate);
        }
    }

    private static PropertyPath indexedChild(PropertyPath parent, int index) {
        List<PropertyPathSegment> segments =
                new ArrayList<PropertyPathSegment>(parent.segments());
        segments.add(PropertyPathSegment.index(index));
        return new PropertyPath(segments);
    }

    private static PropertyPath mapChild(PropertyPath parent, String key) {
        List<PropertyPathSegment> segments =
                new ArrayList<PropertyPathSegment>(parent.segments());
        segments.add(PropertyPathSegment.key(key == null ? "" : key));
        return new PropertyPath(segments);
    }

    private static PropertyPath path(List<PropertyPathSegment> prefix, String name) {
        List<PropertyPathSegment> segments = new ArrayList<PropertyPathSegment>(prefix.size() + 1);
        segments.addAll(prefix);
        segments.add(PropertyPathSegment.property(name));
        return new PropertyPath(segments);
    }

    private static final class Traversal {
        private final Map<PropertyPath, Candidate> candidates =
                new LinkedHashMap<PropertyPath, Candidate>();
        private final IdentityHashMap<TestElement, Boolean> elementStack =
                new IdentityHashMap<TestElement, Boolean>();
    }

    private static final class NestedProperty {
        private final PropertyPath path;
        private final JMeterProperty property;

        private NestedProperty(PropertyPath path, JMeterProperty property) {
            this.path = path;
            this.property = property;
        }
    }

    private static final class Candidate {
        private final PropertyPath path;
        private final ValueBinding binding;
        private final List<RepresentationSource> sources = new ArrayList<RepresentationSource>();

        private Candidate(
                PropertyPath path,
                ValueBinding binding,
                RepresentationSource source) {
            this.path = path;
            this.binding = binding;
            sources.add(source);
        }

        static Candidate runtime(
                PropertyPath path, JMeterProperty property, RuntimeContext runtimeContext) {
            return new Candidate(path, ValueBinding.runtime(property, runtimeContext),
                    RepresentationSource.RUNTIME);
        }

        static Candidate absent(
                PropertyPath path,
                DeclaredPropertyDiscovery.Shape shape,
                RepresentationSource source) {
            return new Candidate(path, ValueBinding.absent(shape), source);
        }

        static Candidate enabled(PropertyPath path, TestElement element) {
            return new Candidate(path, ValueBinding.enabled(element), RepresentationSource.RUNTIME);
        }

        String name() {
            List<PropertyPathSegment> segments = path.segments();
            return segments.get(segments.size() - 1).name();
        }

        void addSource(RepresentationSource source) {
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }

        GraphNode toNode() {
            String name = name();
            boolean system = TestElement.GUI_CLASS.equals(name) || TestElement.TEST_CLASS.equals(name);
            RuntimeClassConstraint constraint = new RuntimeClassConstraint(
                    binding.value.propertyClass(), binding.valueClass);
            GraphCapability capability = new GraphCapability(
                    StorageKeyStatus.NON_KEY,
                    system || !binding.writable
                            ? WritableState.READ_ONLY : WritableState.WRITABLE,
                    system ? SYSTEM_REASON : binding.writable ? null : CODEC_REASON,
                    system ? GraphOwnership.SYSTEM : GraphOwnership.USER,
                    sources.get(0),
                    constraint);
            return new GraphNode(
                    path, binding.value, new GraphNodeProvenance(capability, sources));
        }
    }

    private static final class ValueBinding {
        private final RecursiveValue value;
        private final String valueClass;
        private final boolean writable;

        private ValueBinding(RecursiveValue value, String valueClass, boolean writable) {
            this.value = value;
            this.valueClass = valueClass;
            this.writable = writable;
        }

        static ValueBinding runtime(JMeterProperty property, RuntimeContext runtimeContext) {
            Object objectValue = property.getObjectValue();
            return new ValueBinding(
                    RuntimePropertyValueDiscovery.read(property, runtimeContext),
                    objectValue == null ? null : objectValue.getClass().getName(),
                    true);
        }

        static ValueBinding absent(DeclaredPropertyDiscovery.Shape shape) {
            return new ValueBinding(
                    RecursiveValue.absent(shape.type(), shape.propertyClass()),
                    shape.valueClass(),
                    shape.type().isScalar()
                            || shape.type() == GraphType.COLLECTION);
        }

        static ValueBinding enabled(TestElement element) {
            return new ValueBinding(
                    RecursiveValue.scalar(GraphType.BOOLEAN, BooleanProperty.class.getName(),
                            Boolean.valueOf(element.isEnabled())),
                    Boolean.class.getName(),
                    true);
        }
    }
}
