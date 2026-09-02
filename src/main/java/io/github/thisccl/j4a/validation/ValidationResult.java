package io.github.thisccl.j4a.validation;

import java.util.Optional;

public final class ValidationResult {
    private final boolean valid;
    private final Optional<ValidationErrorCode> errorCode;
    private final String message;
    private final Optional<Throwable> cause;

    public ValidationResult(boolean valid, Optional<ValidationErrorCode> errorCode, String message, Optional<Throwable> cause) {
        this.valid = valid;
        this.errorCode = errorCode;
        this.message = message;
        this.cause = cause;
    }

    public boolean valid() {
        return valid;
    }

    public Optional<ValidationErrorCode> errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }

    public Optional<Throwable> cause() {
        return cause;
    }

    public static ValidationResult success() {
        return new ValidationResult(true, Optional.empty(), "Validation passed", Optional.empty());
    }

    public static ValidationResult invalid(ValidationErrorCode errorCode, String message) {
        return invalid(errorCode, message, null);
    }

    public static ValidationResult invalid(ValidationErrorCode errorCode, String message, Throwable cause) {
        return new ValidationResult(false, Optional.of(errorCode), message, Optional.ofNullable(cause));
    }
}
