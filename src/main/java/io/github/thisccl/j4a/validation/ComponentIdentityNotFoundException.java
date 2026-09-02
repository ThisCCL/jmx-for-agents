package io.github.thisccl.j4a.validation;

final class ComponentIdentityNotFoundException extends IllegalArgumentException {
    ComponentIdentityNotFoundException(String component) {
        super("Component identity is not present uniquely in the current JMeter menu registry: " + component);
    }
}
