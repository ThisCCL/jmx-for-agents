package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyAddress;
import java.util.Objects;

public final class PropertyGraphDocument {
    private final PropertyAddress property;
    private final GraphType type;
    private final RecursiveValue value;

    public PropertyGraphDocument(
            PropertyAddress property, GraphType type, RecursiveValue value) {
        this.property = Objects.requireNonNull(property, "property address is required");
        this.type = Objects.requireNonNull(type, "graph type is required");
        this.value = Objects.requireNonNull(value, "recursive value is required");
        if (value.type() != type) {
            throw new IllegalArgumentException("document type must match recursive value type");
        }
    }

    public PropertyAddress property() {
        return property;
    }

    public GraphType type() {
        return type;
    }

    public RecursiveValue value() {
        return value;
    }

    public PropertyWrite resolve(GraphSnapshot snapshot) {
        GraphNode node = snapshot.resolve(property);
        return new PropertyWrite(node.path(), type, value);
    }
}
