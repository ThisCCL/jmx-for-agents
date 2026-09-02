package io.github.thisccl.j4a.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalJMeterWorkerRequest {
    private final String requestId;
    private final String operation;
    private final String jmxPath;
    private final String jmeterHome;
    private final String component;
    private final String patchPath;
    private final String patchYaml;
    private final String targetPath;
    private final String dryRunCandidateDirectory;
    private final String replaceExisting;
    private final String category;
    private final String details;
    private final String limit;
    private final String maxBytes;
    private final String cursor;
    private final String componentToken;
    private final String diagnostics;
    private final LocalJMeterWorkerReadOptions readOptions;
    private final String locator;
    private final String propertyPath;
    private final String propertyValue;
    private final String propertyType;
    private final String testPlanName;
    private final String threadGroupName;

    LocalJMeterWorkerRequest(
            String requestId,
            String operation,
            String jmxPath,
            String jmeterHome,
            String component,
            String patchPath,
            String patchYaml,
            String targetPath,
            String dryRunCandidateDirectory,
            String replaceExisting,
            String category,
            String details,
            String limit,
            String maxBytes,
            String cursor,
            String componentToken,
            String diagnostics,
            LocalJMeterWorkerReadOptions readOptions,
            String locator,
            String propertyPath,
            String propertyValue,
            String propertyType,
            String testPlanName,
            String threadGroupName) {
        this.requestId = requestId;
        this.operation = operation;
        this.jmxPath = jmxPath;
        this.jmeterHome = jmeterHome;
        this.component = component;
        this.patchPath = patchPath;
        this.patchYaml = patchYaml;
        this.targetPath = targetPath;
        this.dryRunCandidateDirectory = dryRunCandidateDirectory;
        this.replaceExisting = replaceExisting;
        this.category = category;
        this.details = details;
        this.limit = limit;
        this.maxBytes = maxBytes;
        this.cursor = cursor;
        this.componentToken = componentToken;
        this.diagnostics = diagnostics;
        this.readOptions = readOptions;
        this.locator = locator;
        this.propertyPath = propertyPath;
        this.propertyValue = propertyValue;
        this.propertyType = propertyType;
        this.testPlanName = testPlanName;
        this.threadGroupName = threadGroupName;
    }

    public static LocalJMeterWorkerRequest validate(Path jmxPath, Path jmeterHome) {
        return request("validateJmx", jmxPath, jmeterHome);
    }

    public static LocalJMeterWorkerRequest loadJmx(Path jmxPath, Path jmeterHome) {
        return request("loadJmx", jmxPath, jmeterHome);
    }

    public static LocalJMeterWorkerRequest initJmx(
            Path targetPath,
            Path jmeterHome,
            String testPlanName,
            String threadGroupName,
            String readDepth,
            String readPropertyMode) {
        return new LocalJMeterWorkerRequestBuilder("initJmx")
                .jmeterHome(jmeterHome)
                .targetPath(targetPath)
                .testPlanName(testPlanName)
                .threadGroupName(threadGroupName)
                .readDepth(readDepth)
                .readPropertyMode(readPropertyMode)
                .includeDisabledDetails("false")
                .build();
    }

    public static LocalJMeterWorkerRequest renderReadData(Path jmxPath, Path jmeterHome) {
        return renderReadData(jmxPath, jmeterHome, "1", null, "NONE", "false");
    }

    public static LocalJMeterWorkerRequest renderReadData(
            Path jmxPath,
            Path jmeterHome,
            String readDepth,
            String readRef,
            String readPropertyMode,
            String includeDisabledDetails) {
        return new LocalJMeterWorkerRequestBuilder("renderReadData")
                .jmxPath(jmxPath)
                .jmeterHome(jmeterHome)
                .readDepth(readDepth)
                .readRef(readRef)
                .readPropertyMode(readPropertyMode)
                .includeDisabledDetails(includeDisabledDetails)
                .build();
    }

    public static LocalJMeterWorkerRequest discoverComponents(Path jmeterHome) {
        return discoverComponents(jmeterHome, null);
    }

    public static LocalJMeterWorkerRequest discoverComponents(Path jmeterHome, String category) {
        return discoverComponents(jmeterHome, category, false, null, null);
    }

    public static LocalJMeterWorkerRequest discoverComponents(
            Path jmeterHome, String category, boolean details, String limit, String cursor) {
        return discoverComponents(jmeterHome, category, details, limit, null, cursor);
    }

    public static LocalJMeterWorkerRequest discoverComponents(
            Path jmeterHome, String category, boolean details, String limit, String maxBytes, String cursor) {
        return new LocalJMeterWorkerRequestBuilder("discoverComponents")
                .jmeterHome(jmeterHome)
                .category(category)
                .details(Boolean.toString(details))
                .limit(limit)
                .maxBytes(maxBytes)
                .cursor(cursor)
                .build();
    }

    public static LocalJMeterWorkerRequest listCategories(Path jmeterHome) {
        return new LocalJMeterWorkerRequestBuilder("listCategories")
                .jmeterHome(jmeterHome)
                .build();
    }

    public static LocalJMeterWorkerRequest componentDetails(Path jmeterHome, String component) {
        return componentDetails(jmeterHome, component, false);
    }

    public static LocalJMeterWorkerRequest componentDetails(
            Path jmeterHome, String component, boolean diagnostics) {
        return new LocalJMeterWorkerRequestBuilder("componentDetails")
                .jmeterHome(jmeterHome)
                .component(component)
                .diagnostics(Boolean.toString(diagnostics))
                .build();
    }

    public static LocalJMeterWorkerRequest componentDetailsByToken(
            Path jmeterHome, String componentToken) {
        return new LocalJMeterWorkerRequestBuilder("componentDetails")
                .jmeterHome(jmeterHome)
                .componentToken(componentToken)
                .build();
    }

    public static LocalJMeterWorkerRequest applyPatch(Path jmxPath, Path jmeterHome, Path patchPath, Path targetPath) {
        return applyPatch(jmxPath, jmeterHome, patchPath, targetPath, true);
    }

    public static LocalJMeterWorkerRequest applyPatch(
            Path jmxPath, Path jmeterHome, Path patchPath, Path targetPath, boolean replaceExisting) {
        return new LocalJMeterWorkerRequestBuilder("applyPatch")
                .jmxPath(jmxPath)
                .jmeterHome(jmeterHome)
                .patchPath(patchPath)
                .patchYaml(readBoundedPatch(patchPath))
                .targetPath(targetPath)
                .replaceExisting(Boolean.toString(replaceExisting))
                .build();
    }

    public static LocalJMeterWorkerRequest applyPatchYaml(
            Path jmxPath, Path jmeterHome, String patchYaml, Path targetPath, boolean replaceExisting) {
        return new LocalJMeterWorkerRequestBuilder("applyPatch")
                .jmxPath(jmxPath)
                .jmeterHome(jmeterHome)
                .patchYaml(requireBoundedPatch(patchYaml))
                .targetPath(targetPath)
                .replaceExisting(Boolean.toString(replaceExisting))
                .build();
    }

    LocalJMeterWorkerRequest withDryRunCandidateDirectory(Path dryRunCandidateDirectory) {
        String directory = dryRunCandidateDirectory == null
                ? null : dryRunCandidateDirectory.toAbsolutePath().normalize().toString();
        return new LocalJMeterWorkerRequest(requestId, operation, jmxPath, jmeterHome, component, patchPath, patchYaml, targetPath,
                directory, replaceExisting, category, details, limit, maxBytes, cursor, componentToken, diagnostics, readOptions, locator, propertyPath, propertyValue, propertyType, testPlanName,
                threadGroupName);
    }

    LocalJMeterWorkerRequest withRequestId(String value) {
        return new LocalJMeterWorkerRequest(value, operation, jmxPath, jmeterHome, component, patchPath, patchYaml, targetPath,
                dryRunCandidateDirectory, replaceExisting, category, details, limit, maxBytes, cursor, componentToken, diagnostics, readOptions, locator, propertyPath, propertyValue,
                propertyType, testPlanName, threadGroupName);
    }

    public static LocalJMeterWorkerRequest setProperty(
            Path jmxPath,
            Path jmeterHome,
            Path targetPath,
            String locator,
            String propertyPath,
            String propertyValue,
            String propertyType) {
        return setProperty(
                jmxPath, jmeterHome, targetPath, locator, propertyPath, propertyValue, propertyType, true);
    }

    public static LocalJMeterWorkerRequest setProperty(
            Path jmxPath,
            Path jmeterHome,
            Path targetPath,
            String locator,
            String propertyPath,
            String propertyValue,
            String propertyType,
            boolean replaceExisting) {
        return new LocalJMeterWorkerRequestBuilder("setProperty")
                .jmxPath(jmxPath)
                .jmeterHome(jmeterHome)
                .targetPath(targetPath)
                .replaceExisting(Boolean.toString(replaceExisting))
                .locator(locator)
                .propertyPath(propertyPath)
                .propertyValue(propertyValue)
                .propertyType(propertyType)
                .build();
    }

    static LocalJMeterWorkerRequest fromJsonLine(String json) {
        return LocalJMeterWorkerRequestJsonCodec.fromJsonLine(json);
    }

    String toJsonLine() {
        return LocalJMeterWorkerRequestJsonCodec.toJsonLine(this);
    }

    String operation() {
        return operation;
    }

    String requestId() {
        return requestId;
    }

    String jmxPath() {
        return jmxPath;
    }

    String jmeterHome() {
        return jmeterHome;
    }

    String component() {
        return component;
    }

    String patchPath() {
        return patchPath;
    }

    String patchYaml() {
        return patchYaml;
    }

    String targetPath() {
        return targetPath;
    }

    String dryRunCandidateDirectory() {
        return dryRunCandidateDirectory;
    }

    boolean replaceExisting() {
        return replaceExisting == null || Boolean.parseBoolean(replaceExisting);
    }

    String category() {
        return category;
    }

    boolean details() {
        return Boolean.parseBoolean(details);
    }

    String limit() {
        return limit;
    }

    String maxBytes() {
        return maxBytes;
    }

    String cursor() {
        return cursor;
    }

    String componentToken() {
        return componentToken;
    }

    boolean diagnostics() {
        return Boolean.parseBoolean(diagnostics);
    }

    String readDepth() {
        return readOptions.depth();
    }

    String readRef() {
        return readOptions.ref();
    }

    String readPropertyMode() {
        return readOptions.propertyMode();
    }

    String includeDisabledDetails() {
        return readOptions.includeDisabledDetails();
    }

    String locator() {
        return locator;
    }

    String propertyPath() {
        return propertyPath;
    }

    String propertyValue() {
        return propertyValue;
    }

    String propertyType() {
        return propertyType;
    }

    String testPlanName() {
        return testPlanName;
    }

    String threadGroupName() {
        return threadGroupName;
    }

    private static LocalJMeterWorkerRequest request(String operation, Path jmxPath, Path jmeterHome) {
        return new LocalJMeterWorkerRequestBuilder(operation).jmxPath(jmxPath).jmeterHome(jmeterHome).build();
    }

    private static String readBoundedPatch(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 4 * 1024 * 1024) {
                    throw new IllegalArgumentException("PATCH_INPUT_TOO_LARGE: YAML patch exceeds the 4 MiB maximum.");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not snapshot patch input: " + exception.getMessage(), exception);
        }
    }

    static String requireBoundedPatch(String patchYaml) {
        if (patchYaml == null) {
            throw new IllegalArgumentException("Patch YAML is required.");
        }
        if (patchYaml.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("PATCH_INPUT_TOO_LARGE: YAML patch exceeds the 4 MiB maximum.");
        }
        return patchYaml;
    }
}
