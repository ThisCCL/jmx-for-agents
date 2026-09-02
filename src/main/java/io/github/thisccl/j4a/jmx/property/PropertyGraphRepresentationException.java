package io.github.thisccl.j4a.jmx.property;

import java.util.Objects;

public final class PropertyGraphRepresentationException extends IllegalArgumentException {
    private final PropertyGraphRepresentationErrorCode errorCode;
    private final String path;
    private final String reason;

    public PropertyGraphRepresentationException(
            PropertyGraphRepresentationErrorCode errorCode, String path, String reason) {
        super(requireText(path, "representation path") + ": " + requireText(reason, "representation reason"));
        this.errorCode = Objects.requireNonNull(errorCode, "representation error code is required");
        this.path = path;
        this.reason = reason;
    }

    public PropertyGraphRepresentationErrorCode errorCode() {
        return errorCode;
    }

    public String path() {
        return path;
    }

    public String reason() {
        return reason;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
