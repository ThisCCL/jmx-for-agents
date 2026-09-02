package io.github.thisccl.j4a.locator;

public final class LocatorNotFoundException extends IllegalArgumentException {
    private final String locator;

    public LocatorNotFoundException(String locator) {
        super("Unknown locator: " + locator);
        this.locator = locator;
    }

    public String locator() {
        return locator;
    }
}
