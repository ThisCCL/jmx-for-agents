package io.github.thisccl.j4a.validation;

final class LocalJMeterWorkerReadOptions {
    private final String depth;
    private final String ref;
    private final String propertyMode;
    private final String includeDisabledDetails;

    LocalJMeterWorkerReadOptions(
            String depth, String ref, String propertyMode, String includeDisabledDetails) {
        this.depth = depth;
        this.ref = ref;
        this.propertyMode = propertyMode;
        this.includeDisabledDetails = includeDisabledDetails;
    }

    static LocalJMeterWorkerReadOptions fromJsonFields(LocalJMeterWorkerJson.ObjectFields fields) {
        return new LocalJMeterWorkerReadOptions(
                fields.string("readDepth"),
                fields.string("readRef"),
                fields.string("readPropertyMode"),
                fields.string("includeDisabledDetails"));
    }

    void writeTo(LocalJMeterWorkerJson.Builder json) {
        json.field("readDepth", depth);
        json.field("readRef", ref);
        json.field("readPropertyMode", propertyMode);
        json.field("includeDisabledDetails", includeDisabledDetails);
    }

    String depth() {
        return depth;
    }

    String ref() {
        return ref;
    }

    String propertyMode() {
        return propertyMode;
    }

    String includeDisabledDetails() {
        return includeDisabledDetails;
    }
}
