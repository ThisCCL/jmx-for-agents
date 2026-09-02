package io.github.thisccl.j4a.validation;

import java.nio.file.Path;

final class LocalJMeterWorkerRequestBuilder {
    private final String operation;
    private String jmxPath;
    private String jmeterHome;
    private String component;
    private String patchPath;
    private String patchYaml;
    private String targetPath;
    private String dryRunCandidateDirectory;
    private String replaceExisting;
    private String category;
    private String details;
    private String limit;
    private String maxBytes;
    private String cursor;
    private String componentToken;
    private String diagnostics;
    private String readDepth;
    private String readRef;
    private String readPropertyMode;
    private String includeDisabledDetails;
    private String locator;
    private String propertyPath;
    private String propertyValue;
    private String propertyType;
    private String testPlanName;
    private String threadGroupName;

    LocalJMeterWorkerRequestBuilder(String operation) {
        this.operation = operation;
    }

    LocalJMeterWorkerRequestBuilder jmxPath(Path value) {
        this.jmxPath = path(value);
        return this;
    }

    LocalJMeterWorkerRequestBuilder jmeterHome(Path value) {
        this.jmeterHome = path(value);
        return this;
    }

    LocalJMeterWorkerRequestBuilder component(String value) {
        this.component = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder patchPath(Path value) {
        this.patchPath = path(value);
        return this;
    }

    LocalJMeterWorkerRequestBuilder patchYaml(String value) {
        this.patchYaml = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder targetPath(Path value) {
        this.targetPath = path(value);
        return this;
    }

    LocalJMeterWorkerRequestBuilder dryRunCandidateDirectory(Path value) {
        this.dryRunCandidateDirectory = path(value);
        return this;
    }

    LocalJMeterWorkerRequestBuilder replaceExisting(String value) {
        this.replaceExisting = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder category(String value) {
        this.category = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder details(String value) {
        this.details = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder limit(String value) {
        this.limit = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder maxBytes(String value) {
        this.maxBytes = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder cursor(String value) {
        this.cursor = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder componentToken(String value) {
        this.componentToken = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder diagnostics(String value) {
        this.diagnostics = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder readDepth(String value) {
        this.readDepth = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder readRef(String value) {
        this.readRef = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder readPropertyMode(String value) {
        this.readPropertyMode = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder includeDisabledDetails(String value) {
        this.includeDisabledDetails = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder locator(String value) {
        this.locator = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder propertyPath(String value) {
        this.propertyPath = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder propertyValue(String value) {
        this.propertyValue = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder propertyType(String value) {
        this.propertyType = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder testPlanName(String value) {
        this.testPlanName = value;
        return this;
    }

    LocalJMeterWorkerRequestBuilder threadGroupName(String value) {
        this.threadGroupName = value;
        return this;
    }

    LocalJMeterWorkerRequest build() {
        return new LocalJMeterWorkerRequest(
                null,
                operation,
                jmxPath,
                jmeterHome,
                component,
                patchPath,
                patchYaml,
                targetPath,
                dryRunCandidateDirectory,
                replaceExisting,
                category,
                details,
                limit,
                maxBytes,
                cursor,
                componentToken,
                diagnostics,
                new LocalJMeterWorkerReadOptions(
                        readDepth, readRef, readPropertyMode, includeDisabledDetails),
                locator,
                propertyPath,
                propertyValue,
                propertyType,
                testPlanName,
                threadGroupName);
    }

    private static String path(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize().toString();
    }
}
