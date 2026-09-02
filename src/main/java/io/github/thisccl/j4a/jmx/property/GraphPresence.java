package io.github.thisccl.j4a.jmx.property;

public enum GraphPresence {
    PRESENT("present"),
    ABSENT("absent");

    private final String wireName;

    GraphPresence(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static GraphPresence fromWireName(String wireName) {
        for (GraphPresence presence : values()) {
            if (presence.wireName.equals(wireName)) {
                return presence;
            }
        }
        throw new IllegalArgumentException("unrecognized graph presence: " + wireName);
    }
}
