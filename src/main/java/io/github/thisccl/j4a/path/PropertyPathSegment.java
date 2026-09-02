package io.github.thisccl.j4a.path;

import java.util.Objects;

public final class PropertyPathSegment {
    private final Kind kind;
    private final String name;
    private final int index;
    private final boolean quoted;

    public PropertyPathSegment(Kind kind, String name, int index) {
        this(kind, name, index, false);
    }

    private PropertyPathSegment(Kind kind, String name, int index, boolean quoted) {
        if (kind == null) {
            throw new IllegalArgumentException("property path segment kind is required");
        }
        if (kind == Kind.PROPERTY || kind == Kind.KEY) {
            if (name == null) {
                throw new IllegalArgumentException("property path segment name is required");
            }
            if (index != -1) {
                throw new IllegalArgumentException("property segment index must be -1");
            }
        } else {
            if (name != null) {
                throw new IllegalArgumentException("index segment name must be null");
            }
            if (index < 0) {
                throw new IllegalArgumentException("collection index must be zero or greater");
            }
        }
        this.kind = kind;
        this.name = name;
        this.index = index;
        this.quoted = kind == Kind.KEY;
    }

    public enum Kind {
        PROPERTY,
        INDEX,
        KEY
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public int index() {
        return index;
    }

    boolean quoted() {
        return quoted;
    }

    public static PropertyPathSegment property(String name) {
        if (name == null) {
            throw new IllegalArgumentException("property segment name is required");
        }
        return new PropertyPathSegment(Kind.PROPERTY, name, -1);
    }

    static PropertyPathSegment quotedProperty(String name) {
        return key(name);
    }

    public static PropertyPathSegment key(String name) {
        if (name == null) {
            throw new IllegalArgumentException("key segment name is required");
        }
        return new PropertyPathSegment(Kind.KEY, name, -1, true);
    }

    public static PropertyPathSegment index(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("collection index must be zero or greater");
        }
        return new PropertyPathSegment(Kind.INDEX, null, index);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PropertyPathSegment)) {
            return false;
        }
        PropertyPathSegment that = (PropertyPathSegment) other;
        return index == that.index
                && quoted == that.quoted
                && kind == that.kind
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, name, index, quoted);
    }

    @Override
    public String toString() {
        return "PropertyPathSegment[kind=" + kind + ", name=" + name + ", index=" + index
                + ", quoted=" + quoted + "]";
    }
}
