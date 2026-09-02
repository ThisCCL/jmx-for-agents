package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.path.PropertyAddressDocument;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathErrorCode;
import io.github.thisccl.j4a.path.PropertyPathResolutionException;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GraphSnapshot {
    private final RuntimeContext runtimeContext;
    private final List<GraphNode> nodes;

    public GraphSnapshot(RuntimeContext runtimeContext, List<GraphNode> nodes) {
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtime context is required");
        Objects.requireNonNull(nodes, "graph nodes are required");
        ArrayList<GraphNode> copy = new ArrayList<GraphNode>(nodes.size());
        Set<PropertyPath> paths = new HashSet<PropertyPath>();
        for (GraphNode node : nodes) {
            GraphNode requiredNode = Objects.requireNonNull(node, "graph nodes must not contain null");
            if (!paths.add(requiredNode.path())) {
                throw new IllegalArgumentException(
                        "graph node paths must be unique: " + source(requiredNode.path()));
            }
            copy.add(requiredNode);
        }
        this.nodes = Collections.unmodifiableList(copy);
    }

    public RuntimeContext runtimeContext() {
        return runtimeContext;
    }

    public List<GraphNode> nodes() {
        return nodes;
    }

    public Optional<GraphNode> find(PropertyPath path) {
        Objects.requireNonNull(path, "property path is required");
        for (GraphNode node : nodes) {
            if (path.equals(node.path())) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    public GraphNode resolve(PropertyPath path) {
        Objects.requireNonNull(path, "property path is required");
        Optional<GraphNode> node = find(path);
        if (node.isPresent()) {
            return node.get();
        }
        throw new PropertyPathResolutionException(
                source(path),
                PropertyPathErrorCode.MISSING_PROPERTY,
                lastSegment(path),
                "unresolved property segment '" + lastSegment(path) + "'");
    }

    public GraphNode resolve(PropertyAddress address) {
        Objects.requireNonNull(address, "property address is required");
        for (GraphNode node : nodes) {
            if (address.equals(PropertyAddress.fromPath(node.path()))) {
                return node;
            }
        }
        ArrayList<PropertyPathSegment> resolved = new ArrayList<PropertyPathSegment>();
        GraphType currentType = GraphType.ELEMENT;
        GraphNode currentNode = null;
        for (Object segment : address.segments()) {
            PropertyPathSegment typedSegment = resolveSegment(address, currentType, segment);
            resolved.add(typedSegment);
            PropertyPath currentPath = new PropertyPath(resolved);
            Optional<GraphNode> matched = find(currentPath);
            if (!matched.isPresent()) {
                PropertyPathErrorCode code = currentType == GraphType.COLLECTION
                        ? PropertyPathErrorCode.WRONG_INDEX
                        : PropertyPathErrorCode.MISSING_PROPERTY;
                throw new PropertyPathResolutionException(
                        address.toString(),
                        code,
                        display(segment),
                        "property address does not resolve at segment '" + display(segment) + "'");
            }
            currentNode = matched.get();
            currentType = currentNode.type();
        }
        return currentNode;
    }

    public GraphNode resolveWritable(PropertyPath path) {
        GraphNode node = resolve(path);
        if (node.capability().writable()) {
            return node;
        }
        String reason = node.capability().reason().orElse("property is not writable");
        throw new IllegalArgumentException(
                "Property '" + source(path) + "' is read-only: " + reason);
    }

    private static String lastSegment(PropertyPath path) {
        PropertyPathSegment segment = path.segments().get(path.segments().size() - 1);
        return segment.kind() == PropertyPathSegment.Kind.PROPERTY
                || segment.kind() == PropertyPathSegment.Kind.KEY
                ? segment.name()
                : "[" + segment.index() + "]";
    }

    private static PropertyPathSegment resolveSegment(
            PropertyAddress address, GraphType currentType, Object segment) {
        if (currentType == GraphType.ELEMENT) {
            if (segment instanceof String) {
                return PropertyPathSegment.property((String) segment);
            }
            throw wrongType(address, currentType, segment, "string property name");
        }
        if (currentType == GraphType.MAP) {
            if (segment instanceof String) {
                return PropertyPathSegment.key((String) segment);
            }
            throw wrongType(address, currentType, segment, "string map key");
        }
        if (currentType == GraphType.COLLECTION) {
            if (segment instanceof Integer) {
                return PropertyPathSegment.index(((Integer) segment).intValue());
            }
            throw wrongType(address, currentType, segment, "integer collection index");
        }
        throw wrongType(address, currentType, segment, "no further segment");
    }

    private static PropertyPathResolutionException wrongType(
            PropertyAddress address, GraphType currentType, Object segment, String expected) {
        return new PropertyPathResolutionException(
                address.toString(),
                PropertyPathErrorCode.WRONG_TYPE,
                display(segment),
                "property address segment '" + display(segment) + "' is invalid for "
                        + currentType.wireName() + "; expected " + expected);
    }

    private static String display(Object segment) {
        return String.valueOf(segment);
    }

    private static String source(PropertyPath path) {
        return PropertyAddress.fromPath(path).toString();
    }
}
