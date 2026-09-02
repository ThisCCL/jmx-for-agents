package io.github.thisccl.j4a.jmx.property;

public enum GraphOwnership {
    USER("user"),
    SYSTEM("system");

    private final String wireName;

    GraphOwnership(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
