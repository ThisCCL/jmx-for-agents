package io.github.thisccl.j4a.validation;

final class JMeterPlacementRejectedException extends IllegalArgumentException {
    JMeterPlacementRejectedException(String parent, String child) {
        super("JMeter rejected placement of " + child + " under " + parent);
    }
}
