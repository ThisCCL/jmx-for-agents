package io.github.thisccl.j4a.path;

public class PropertyPathException extends RuntimeException {
    private final String path;
    private final PropertyPathErrorCode errorCode;

    public PropertyPathException(String path, String message) {
        this(path, PropertyPathErrorCode.MALFORMED_PATH, message);
    }

    protected PropertyPathException(String path, PropertyPathErrorCode errorCode, String message) {
        super(message + " in property path '" + path + "'");
        this.path = path;
        this.errorCode = errorCode;
    }

    public String path() {
        return path;
    }

    public PropertyPathErrorCode errorCode() {
        return errorCode;
    }
}
