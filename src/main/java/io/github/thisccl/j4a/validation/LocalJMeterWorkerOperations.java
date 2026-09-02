package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.read.ReadOptions;
import io.github.thisccl.j4a.read.YamlReadRenderer;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

final class LocalJMeterWorkerOperations {
    private final SessionReferenceRegistry sessionRegistry;
    private final LocalJMeterWorkerOrderCapability orderCapability;

    private LocalJMeterWorkerOperations(
            SessionReferenceRegistry sessionRegistry,
            LocalJMeterWorkerOrderCapability orderCapability) {
        this.sessionRegistry = sessionRegistry;
        this.orderCapability = orderCapability;
    }

    static LocalJMeterWorkerOperations forStartupMode(String mode) {
        if (mode == null) {
            return snapshot();
        }
        if (LocalJMeterWorkerClient.SESSION_REFERENCE_MODE.equals(mode)) {
            return session(new SessionReferenceRegistry(), LocalJMeterWorkerOrderCapability.production());
        }
        throw new IllegalArgumentException("Unsupported local worker reference mode: " + mode);
    }

    static LocalJMeterWorkerOperations snapshot() {
        return new LocalJMeterWorkerOperations(null, null);
    }

    static LocalJMeterWorkerOperations session(
            SessionReferenceRegistry registry, LocalJMeterWorkerOrderCapability orderCapability) {
        if (registry == null) {
            throw new NullPointerException("registry");
        }
        if (orderCapability == null) {
            throw new NullPointerException("orderCapability");
        }
        return new LocalJMeterWorkerOperations(registry, orderCapability);
    }

    String execute(LocalJMeterWorkerRequest request) throws Exception {
        LocalJMeterWorkerRuntime.initialize(Paths.get(request.jmeterHome()));
        requireSessionMutationOrder(request.operation());
        if ("validateJmx".equals(request.operation()) || "loadJmx".equals(request.operation())) {
            validate(Paths.get(request.jmxPath()), Paths.get(request.jmeterHome()));
            return "";
        }
        if ("renderReadData".equals(request.operation())) {
            return renderReadData(request);
        }
        if ("discoverComponents".equals(request.operation())) {
            return LocalJMeterWorkerComponents.discoverComponents(request);
        }
        if ("listCategories".equals(request.operation())) {
            return LocalJMeterWorkerComponents.listCategories(request);
        }
        if ("componentDetails".equals(request.operation())) {
            return LocalJMeterWorkerComponents.componentDetails(request);
        }
        if ("applyPatch".equals(request.operation())) {
            if (sessionRegistry == null) {
                return LocalJMeterWorkerMutations.applyPatch(request);
            } else {
                return LocalJMeterWorkerMutations.applyPatch(request, sessionRegistry);
            }
        }
        if ("setProperty".equals(request.operation())) {
            if (sessionRegistry == null) {
                LocalJMeterWorkerMutations.setProperty(request);
            } else {
                LocalJMeterWorkerMutations.setProperty(request, sessionRegistry);
            }
            return "";
        }
        if ("initJmx".equals(request.operation())) {
            return initJmx(request);
        }
        throw new IllegalArgumentException("Unsupported local worker operation: " + request.operation());
    }

    void preflight(LocalJMeterWorkerRequest request) throws Exception {
        Path jmeterHome = Paths.get(request.jmeterHome());
        LocalJMeterWorkerRuntime.initialize(jmeterHome);
        LocalPropertyGraphRuntimeContext.prewarm(jmeterHome);
        if ("applyPatch".equals(request.operation())
                || "renderReadData".equals(request.operation())) {
            LocalJMeterWorkerComponents.prewarmRegistry(jmeterHome);
        }
    }

    static void validate(Path jmxPath, Path jmeterHome) throws Exception {
        JmxTestPlan testPlan = LocalJMeterWorkerJmx.load(jmxPath, jmeterHome);
        Path directory = jmxPath.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            directory = Paths.get(".").toAbsolutePath().normalize();
        }
        Path candidate = Files.createTempFile(directory, "jmx-agent-worker-validate-", ".jmx");
        try {
            LocalJMeterWorkerJmx.save(testPlan, candidate);
            LocalJMeterWorkerJmx.load(candidate, jmeterHome);
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    private void requireSessionMutationOrder(String operation) throws ReferenceFailure {
        if (sessionRegistry == null) {
            return;
        }
        if ("setProperty".equals(operation) || "applyPatch".equals(operation) || "initJmx".equals(operation)) {
            orderCapability.requireProven();
        }
    }

    private String initJmx(LocalJMeterWorkerRequest request) throws Exception {
        if (sessionRegistry == null) {
            return LocalJMeterWorkerMutations.initJmx(request);
        }
        return LocalJMeterWorkerMutations.initJmx(request, sessionRegistry);
    }

    private String renderReadData(LocalJMeterWorkerRequest request) throws Exception {
        if (sessionRegistry == null) {
            JmxTestPlan testPlan = LocalJMeterWorkerJmx.load(
                    Paths.get(request.jmxPath()), Paths.get(request.jmeterHome()));
            BoundReferences references = ReferenceBindings.snapshot(testPlan);
            return new YamlReadRenderer().render(
                    testPlan,
                    readOptions(request),
                    references,
                    LocalPropertyGraphRuntimeContext.selected(Paths.get(request.jmeterHome())),
                    new LocalStructuredRowEvidenceResolver());
        }
        return renderSessionReadData(request);
    }

    private String renderSessionReadData(LocalJMeterWorkerRequest request) throws Exception {
        Path source = Paths.get(request.jmxPath());
        Path jmeterHome = Paths.get(request.jmeterHome());
        DocumentIdentity identity = DocumentIdentity.of(jmeterHome, source);
        String focus = request.readRef();
        SourceSnapshot<JmxTestPlan> snapshot;
        try {
            snapshot = sourceSnapshot(source, jmeterHome);
        } catch (IOException exception) {
            if (focus != null) {
                invalidateSessionDocument(identity);
                throw ReferenceFailure.notFound();
            }
            throw exception;
        }
        if (focus != null) {
            SessionReferenceRegistry.DocumentStatus status =
                    sessionRegistry.documentStatus(identity, snapshot.fingerprint());
            if (status == SessionReferenceRegistry.DocumentStatus.MISMATCH) {
                invalidateSessionDocument(identity);
                throw ReferenceFailure.notFound();
            }
            if (status == SessionReferenceRegistry.DocumentStatus.ABSENT) {
                throw ReferenceFailure.notFound();
            }
        }
        SessionReferenceRegistry.PreparedState prepared = sessionRegistry.prepare(identity);
        boolean published = false;
        try {
            SessionReferenceSpace referenceSpace = new SessionReferenceSpace(prepared, identity, snapshot);
            BoundReferences references = referenceSpace.bind(snapshot.parsed());
            if (focus != null) {
                ReferenceResolution resolution = references.resolve(focus);
                if (resolution.status() != ReferenceResolution.Status.RESOLVED) {
                    if (resolution.unavailableReason().orElse(null)
                            == ReferenceResolution.UnavailableReason.RETAINED_RECORD_PROOF_LOSS) {
                        invalidateSessionDocument(identity);
                    }
                    throw ReferenceFailure.notFound();
                }
            }
            String rendered = new YamlReadRenderer().render(
                    snapshot.parsed(),
                    readOptions(request),
                    references,
                    LocalPropertyGraphRuntimeContext.selected(jmeterHome),
                    new LocalStructuredRowEvidenceResolver());
            if (!sessionRegistry.publish(referenceSpace.prepareSuccessfulUse())) {
                throw new IllegalStateException("Session reference state changed during serialized worker operation");
            }
            published = true;
            return rendered;
        } catch (SessionReferenceRegistry.CapacityExceededException exception) {
            throw ReferenceFailure.capacityExceeded(exception);
        } finally {
            if (!published) {
                sessionRegistry.discard(prepared);
            }
        }
    }

    private void invalidateSessionDocument(DocumentIdentity identity) {
        SessionReferenceRegistry.PreparedState invalidation = sessionRegistry.prepareInvalidation(identity);
        if (!sessionRegistry.publish(invalidation)) {
            sessionRegistry.discard(invalidation);
            throw new IllegalStateException(
                    "Session reference state changed during serialized worker invalidation");
        }
    }

    private static SourceSnapshot<JmxTestPlan> sourceSnapshot(final Path source, final Path jmeterHome)
            throws IOException {
        return SourceSnapshot.read(source, new SourceSnapshot.Parser<JmxTestPlan>() {
            @Override
            public JmxTestPlan parse(InputStream immutableBytes) throws IOException {
                Path temporary = Files.createTempFile("jmx-agent-worker-source-snapshot-", ".jmx");
                try {
                    Files.copy(immutableBytes, temporary, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        return LocalJMeterWorkerJmx.load(temporary, jmeterHome);
                    } catch (IOException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        throw new IOException("Unable to parse immutable JMX source snapshot", exception);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        });
    }

    private static ReadOptions readOptions(LocalJMeterWorkerRequest request) {
        Integer depth = request.readDepth() == null ? null : Integer.valueOf(request.readDepth());
        ReadOptions.PropertyMode propertyMode = request.readPropertyMode() == null
                ? ReadOptions.PropertyMode.NONE
                : ReadOptions.PropertyMode.valueOf(request.readPropertyMode());
        boolean includeDisabledDetails = Boolean.parseBoolean(request.includeDisabledDetails());
        return new ReadOptions(false, includeDisabledDetails, depth, request.readRef(), propertyMode);
    }
}
