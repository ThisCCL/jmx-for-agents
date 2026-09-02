package io.github.thisccl.j4a.path;

import java.util.List;

public final class PropertyPathResolver {
    public void set(PropertyTreeNode root, PropertyPath path, String value, PropertyValueType type) {
        if (root == null) {
            throw new IllegalArgumentException("root property node is required");
        }
        if (value == null) {
            throw new IllegalArgumentException("property value is required");
        }

        PropertyTreeNode target = requireScalar(root, path);
        target.setValue(convert(source(path), value, type == null ? PropertyValueType.STRING : type));
    }

    public PropertyTreeNode requireScalar(PropertyTreeNode root, PropertyPath propertyPath) {
        if (root == null) {
            throw new IllegalArgumentException("root property node is required");
        }
        String source = source(propertyPath);
        PropertyTreeNode target = resolve(root, propertyPath, source);
        if (!target.scalar()) {
            throw new PropertyPathResolutionException(
                    source,
                    PropertyPathErrorCode.WRONG_TYPE,
                    lastSegment(propertyPath),
                    "cannot set non-scalar property segment '" + lastSegment(propertyPath) + "'");
        }
        return target;
    }

    private PropertyTreeNode resolve(PropertyTreeNode root, PropertyPath propertyPath, String source) {
        PropertyTreeNode current = root;
        List<PropertyPathSegment> segments = propertyPath.segments();
        for (PropertyPathSegment segment : segments) {
            switch (segment.kind()) {
                case PROPERTY:
                    current = resolveProperty(source, current, segment.name());
                    break;
                case INDEX:
                    current = resolveIndex(source, current, segment.index());
                    break;
                case KEY:
                    current = resolveProperty(source, current, segment.name());
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported property path segment kind: " + segment.kind());
            }
        }
        return current;
    }

    private PropertyTreeNode resolveProperty(String source, PropertyTreeNode current, String name) {
        if (current.scalar()) {
            throw new PropertyPathResolutionException(
                    source,
                    PropertyPathErrorCode.WRONG_TYPE,
                    name,
                    "cannot resolve property segment '" + name + "' from scalar value");
        }
        return current.child(name)
                .orElseThrow(() -> new PropertyPathResolutionException(
                        source,
                        PropertyPathErrorCode.MISSING_PROPERTY,
                        name,
                        "unresolved property segment '" + name + "'"));
    }

    private PropertyTreeNode resolveIndex(String source, PropertyTreeNode current, int index) {
        if (current.scalar()) {
            throw new PropertyPathResolutionException(
                    source,
                    PropertyPathErrorCode.WRONG_TYPE,
                    "[" + index + "]",
                    "cannot resolve collection index [" + index + "] from scalar value");
        }
        return current.element(index)
                .orElseThrow(() -> new PropertyPathResolutionException(
                        source,
                        PropertyPathErrorCode.WRONG_INDEX,
                        "[" + index + "]",
                        "unresolved collection index [" + index + "]"));
    }

    private PropertyValue convert(String path, String value, PropertyValueType type) {
        try {
            if (type.structuredCollection()) {
                throw new IllegalArgumentException("Structured " + type.name().toLowerCase().replace('_', ' ')
                        + " must use the shared structured-property service");
            }
            switch (type) {
                case STRING:
                    return PropertyValue.string(value);
                case RAW:
                    return PropertyValue.raw(value);
                case BOOLEAN:
                    return convertBoolean(path, value);
                case INT:
                    return PropertyValue.integer(Integer.parseInt(value));
                case LONG:
                    return PropertyValue.longValue(Long.parseLong(value));
                case DOUBLE:
                    return PropertyValue.doubleValue(Double.parseDouble(value), value);
                default:
                    throw new IllegalArgumentException("Unsupported property value type: " + type);
            }
        } catch (NumberFormatException exception) {
            throw conversionException(path, type);
        }
    }

    private PropertyValue convertBoolean(String path, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return PropertyValue.bool(true);
        }
        if ("false".equalsIgnoreCase(value)) {
            return PropertyValue.bool(false);
        }
        throw conversionException(path, PropertyValueType.BOOLEAN);
    }

    private PropertyPathResolutionException conversionException(String path, PropertyValueType type) {
        return new PropertyPathResolutionException(
                path,
                PropertyPathErrorCode.WRONG_TYPE,
                type.name().toLowerCase(),
                "cannot convert value to " + type.name().toLowerCase());
    }

    private String lastSegment(PropertyPath propertyPath) {
        PropertyPathSegment segment = propertyPath.segments().get(propertyPath.segments().size() - 1);
        if (segment.kind() == PropertyPathSegment.Kind.PROPERTY
                || segment.kind() == PropertyPathSegment.Kind.KEY) {
            return segment.name();
        }
        return "[" + segment.index() + "]";
    }

    private static String source(PropertyPath path) {
        return PropertyAddress.fromPath(path).toString();
    }
}
