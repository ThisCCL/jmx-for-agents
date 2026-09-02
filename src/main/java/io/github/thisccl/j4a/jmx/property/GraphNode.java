package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class GraphNode {
    private final PropertyPath path;
    private final GraphType type;
    private final RecursiveValue value;
    private final GraphCapability capability;
    private final List<RepresentationSource> provenance;

    public GraphNode(
            PropertyPath path, GraphType type, RecursiveValue value, GraphCapability capability) {
        this.path = Objects.requireNonNull(path, "property path is required");
        this.type = Objects.requireNonNull(type, "graph type is required");
        this.value = Objects.requireNonNull(value, "recursive value is required");
        this.capability = Objects.requireNonNull(capability, "graph capability is required");
        validate(type, value, capability);
        this.provenance = Collections.singletonList(capability.representationSource());
    }

    GraphNode(PropertyPath path, RecursiveValue value, GraphNodeProvenance provenance) {
        this.path = Objects.requireNonNull(path, "property path is required");
        this.value = Objects.requireNonNull(value, "recursive value is required");
        this.type = value.type();
        GraphNodeProvenance required = Objects.requireNonNull(
                provenance, "graph provenance is required");
        this.capability = required.capability();
        validate(type, value, capability);
        this.provenance = required.sources();
    }

    private static void validate(
            GraphType type, RecursiveValue value, GraphCapability capability) {
        if (type != value.type()) {
            throw new IllegalArgumentException("node type must match recursive value type");
        }
        if (!value.propertyClass().equals(capability.runtimeClassConstraint().propertyClass())) {
            throw new IllegalArgumentException("node property class must match its runtime constraint");
        }
    }

    public PropertyPath path() {
        return path;
    }

    public GraphType type() {
        return type;
    }

    public RecursiveValue value() {
        return value;
    }

    public GraphCapability capability() {
        return capability;
    }

    public List<RepresentationSource> provenance() {
        return provenance;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GraphNode)) {
            return false;
        }
        GraphNode that = (GraphNode) other;
        return path.segments().equals(that.path.segments())
                && type == that.type
                && value.equals(that.value)
                && capability.equals(that.capability)
                && provenance.equals(that.provenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path.segments(), type, value, capability, provenance);
    }

}
