package io.github.thisccl.j4a.path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PropertyAddress {
    private static final BigInteger MAX_INDEX = BigInteger.valueOf(Integer.MAX_VALUE);

    private final List<Object> segments;

    public PropertyAddress(List<?> segments) {
        if (segments == null) {
            throw new IllegalArgumentException("property address segments are required");
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("property address requires at least one segment");
        }
        ArrayList<Object> copy = new ArrayList<Object>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            copy.add(normalize(segments.get(index), index));
        }
        this.segments = Collections.unmodifiableList(copy);
    }

    public static PropertyAddress decode(Object document) {
        if (!(document instanceof List<?>)) {
            throw new IllegalArgumentException("property address must be a non-empty scalar array");
        }
        return new PropertyAddress((List<?>) document);
    }

    public static PropertyAddress fromPath(PropertyPath path) {
        if (path == null) {
            throw new IllegalArgumentException("property path is required");
        }
        ArrayList<Object> segments = new ArrayList<Object>(path.segments().size());
        for (PropertyPathSegment segment : path.segments()) {
            switch (segment.kind()) {
                case PROPERTY:
                case KEY:
                    segments.add(segment.name());
                    break;
                case INDEX:
                    segments.add(Integer.valueOf(segment.index()));
                    break;
                default:
                    throw new IllegalStateException(
                            "unhandled property path segment kind: " + segment.kind());
            }
        }
        return new PropertyAddress(segments);
    }

    public List<Object> segments() {
        return segments;
    }

    private static Object normalize(Object segment, int position) {
        if (segment instanceof String) {
            return segment;
        }
        if (!(segment instanceof Byte || segment instanceof Short || segment instanceof Integer
                || segment instanceof Long || segment instanceof BigInteger)) {
            throw invalid(position, "must be a string or integer");
        }
        BigInteger integer = segment instanceof BigInteger
                ? (BigInteger) segment
                : BigInteger.valueOf(((Number) segment).longValue());
        if (integer.signum() < 0) {
            throw invalid(position, "integer must be zero or greater");
        }
        if (integer.compareTo(MAX_INDEX) > 0) {
            throw invalid(position, "integer exceeds 2147483647");
        }
        return Integer.valueOf(integer.intValue());
    }

    private static IllegalArgumentException invalid(int position, String detail) {
        return new IllegalArgumentException(
                "property address segment[" + position + "] " + detail);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PropertyAddress)) {
            return false;
        }
        PropertyAddress that = (PropertyAddress) other;
        return segments.equals(that.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments);
    }

    @Override
    public String toString() {
        return segments.toString();
    }
}
