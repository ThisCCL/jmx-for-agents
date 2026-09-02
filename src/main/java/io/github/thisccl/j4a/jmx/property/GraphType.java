package io.github.thisccl.j4a.jmx.property;

public enum GraphType {
    STRING("string", true, true),
    BOOLEAN("boolean", true, true),
    INT("int", true, true),
    LONG("long", true, true),
    FLOAT("float", true, true),
    DOUBLE("double", true, true),
    NULL("null", true, false),
    COLLECTION("collection", false, false),
    MAP("map", false, false),
    ELEMENT("element", false, false),
    OPAQUE("opaque", false, false);

    private final String wireName;
    private final boolean scalar;
    private final boolean mapKeyScalar;

    GraphType(String wireName, boolean scalar, boolean mapKeyScalar) {
        this.wireName = wireName;
        this.scalar = scalar;
        this.mapKeyScalar = mapKeyScalar;
    }

    public String wireName() {
        return wireName;
    }

    public boolean isScalar() {
        return scalar;
    }

    public boolean isMapKeyScalar() {
        return mapKeyScalar;
    }

    public static GraphType fromWireName(String wireName) {
        for (GraphType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unrecognized graph type: " + wireName);
    }
}
