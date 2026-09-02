package io.github.thisccl.j4a.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PropertyPath {
    private final List<PropertyPathSegment> segments;

    public PropertyPath(List<PropertyPathSegment> segments) {
        if (segments == null) {
            throw new IllegalArgumentException("property path segments are required");
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("property path requires at least one segment");
        }
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index) == null) {
                throw new IllegalArgumentException(
                        "property path segment at index " + index + " is required");
            }
        }
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    }

    public List<PropertyPathSegment> segments() {
        return segments;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PropertyPath)) {
            return false;
        }
        PropertyPath that = (PropertyPath) other;
        return segments.equals(that.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments);
    }

}
