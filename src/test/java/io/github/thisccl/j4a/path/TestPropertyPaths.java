package io.github.thisccl.j4a.path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TestPropertyPaths {
    private TestPropertyPaths() {
    }

    public static PropertyPath properties(Object... members) {
        List<PropertyPathSegment> segments = new ArrayList<PropertyPathSegment>(members.length);
        for (Object member : members) {
            if (member instanceof String) {
                segments.add(PropertyPathSegment.property((String) member));
            } else if (member instanceof Integer) {
                segments.add(PropertyPathSegment.index(((Integer) member).intValue()));
            } else {
                throw new IllegalArgumentException("typed test path member must be a string or integer");
            }
        }
        return new PropertyPath(segments);
    }

    public static PropertyPath path(PropertyPathSegment... segments) {
        return new PropertyPath(Arrays.asList(segments));
    }

    public static PropertyPathSegment property(String name) {
        return PropertyPathSegment.property(name);
    }

    public static PropertyPathSegment key(String name) {
        return PropertyPathSegment.key(name);
    }

    public static PropertyPathSegment index(int index) {
        return PropertyPathSegment.index(index);
    }
}
