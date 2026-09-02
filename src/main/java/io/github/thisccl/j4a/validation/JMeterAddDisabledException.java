package io.github.thisccl.j4a.validation;

final class JMeterAddDisabledException extends IllegalArgumentException {
    JMeterAddDisabledException(String component) {
        super("JMeter disabled the Add action for component: " + component);
    }
}
