package io.github.thisccl.j4a.path;

public final class PropertyPathResolutionException extends PropertyPathException {
    private final String segment;

    public PropertyPathResolutionException(String path, PropertyPathErrorCode errorCode, String segment, String message) {
        super(path, errorCode, message);
        this.segment = segment;
    }

    public String segment() {
        return segment;
    }
}
