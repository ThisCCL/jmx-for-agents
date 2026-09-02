package io.github.thisccl.j4a.validation;

final class LocalJMeterWorkerRequestJsonCodec {
    private LocalJMeterWorkerRequestJsonCodec() {
    }

    static LocalJMeterWorkerRequest fromJsonLine(String json) {
        LocalJMeterWorkerJson.ObjectFields fields = LocalJMeterWorkerJson.parseObject(json);
        fields.reject("propertyAddressMode");
        return new LocalJMeterWorkerRequest(
                fields.string("requestId"),
                fields.string("operation"),
                fields.string("jmxPath"),
                fields.string("jmeterHome"),
                fields.string("component"),
                fields.string("patchPath"),
                boundedPatch(fields.string("patchYaml")),
                fields.string("targetPath"),
                fields.string("dryRunCandidateDirectory"),
                fields.string("replaceExisting"),
                fields.string("category"),
                fields.string("details"),
                fields.string("limit"),
                fields.string("maxBytes"),
                fields.string("cursor"),
                fields.string("componentToken"),
                fields.string("diagnostics"),
                LocalJMeterWorkerReadOptions.fromJsonFields(fields),
                fields.string("locator"),
                fields.string("propertyPath"),
                fields.string("propertyValue"),
                fields.string("propertyType"),
                fields.string("testPlanName"),
                fields.string("threadGroupName"));
    }

    static String toJsonLine(LocalJMeterWorkerRequest request) {
        LocalJMeterWorkerJson.Builder json = LocalJMeterWorkerJson.object();
        json.field("requestId", request.requestId());
        json.field("operation", request.operation());
        json.field("jmxPath", request.jmxPath());
        json.field("jmeterHome", request.jmeterHome());
        json.field("component", request.component());
        json.field("patchPath", request.patchPath());
        json.field("patchYaml", request.patchYaml());
        json.field("targetPath", request.targetPath());
        json.field("dryRunCandidateDirectory", request.dryRunCandidateDirectory());
        json.field("replaceExisting", Boolean.toString(request.replaceExisting()));
        json.field("category", request.category());
        json.field("details", Boolean.toString(request.details()));
        json.field("limit", request.limit());
        json.field("maxBytes", request.maxBytes());
        json.field("cursor", request.cursor());
        json.field("componentToken", request.componentToken());
        json.field("diagnostics", Boolean.toString(request.diagnostics()));
        json.field("readDepth", request.readDepth());
        json.field("readRef", request.readRef());
        json.field("readPropertyMode", request.readPropertyMode());
        json.field("includeDisabledDetails", request.includeDisabledDetails());
        json.field("locator", request.locator());
        json.field("propertyPath", request.propertyPath());
        json.field("propertyValue", request.propertyValue());
        json.field("propertyType", request.propertyType());
        json.field("testPlanName", request.testPlanName());
        json.field("threadGroupName", request.threadGroupName());
        return json.build();
    }

    private static String boundedPatch(String patchYaml) {
        return patchYaml == null ? null : LocalJMeterWorkerRequest.requireBoundedPatch(patchYaml);
    }
}
