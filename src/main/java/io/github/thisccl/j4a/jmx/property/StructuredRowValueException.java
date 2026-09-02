package io.github.thisccl.j4a.jmx.property;

final class StructuredRowValueException extends IllegalArgumentException {
    StructuredRowValueException(String message) {
        super(message.length() <= 512 ? message : message.substring(0, 512));
    }
}
