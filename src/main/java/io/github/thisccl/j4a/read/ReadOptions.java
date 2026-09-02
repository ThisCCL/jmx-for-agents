package io.github.thisccl.j4a.read;

public final class ReadOptions {
    private final boolean verbose;
    private final boolean includeDisabledDetails;
    private final Integer depth;
    private final String ref;
    private final PropertyMode propertyMode;

    public ReadOptions(boolean verbose, boolean includeDisabledDetails) {
        this(verbose, includeDisabledDetails, 1, null, verbose ? PropertyMode.ALL : PropertyMode.NONE);
    }

    public ReadOptions(boolean verbose, boolean includeDisabledDetails, Integer depth, String ref, PropertyMode propertyMode) {
        this.verbose = verbose;
        this.includeDisabledDetails = includeDisabledDetails;
        this.depth = depth;
        this.ref = ref;
        this.propertyMode = propertyMode;
    }

    public boolean verbose() {
        return verbose;
    }

    public boolean includeDisabledDetails() {
        return includeDisabledDetails;
    }

    public Integer depth() {
        return depth;
    }

    public String ref() {
        return ref;
    }

    public PropertyMode propertyMode() {
        return propertyMode;
    }

    public enum PropertyMode {
        NONE,
        KEY,
        ALL,
        WRITABLE
    }
}
