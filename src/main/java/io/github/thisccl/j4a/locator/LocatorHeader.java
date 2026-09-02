package io.github.thisccl.j4a.locator;

public final class LocatorHeader {
    private final String name;
    private final String value;

    public LocatorHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String name() {
        return name;
    }

    public String value() {
        return value;
    }
}
