package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.apply.MutationOutcome;
import io.github.thisccl.j4a.apply.MutationChangeResult;
import io.github.thisccl.j4a.jmx.JmxInitialPlanFactory;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.read.ReadOptions;
import io.github.thisccl.j4a.read.YamlReadRenderer;
import io.github.thisccl.j4a.reference.BoundReferences;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.testelement.TestElement;
import org.yaml.snakeyaml.Yaml;

final class LocalJMeterWorkerMutations {
    private LocalJMeterWorkerMutations() {
    }

    static String applyPatch(LocalJMeterWorkerRequest request) throws Exception {
        return render(execute(request, parsePatch(request.patchYaml()), null));
    }

    static String applyPatch(
            LocalJMeterWorkerRequest request,
            SessionReferenceRegistry registry) throws Exception {
        return render(execute(request, parsePatch(request.patchYaml()), registry));
    }

    static void setProperty(LocalJMeterWorkerRequest request) throws Exception {
        execute(request, setPatch(request), null);
    }

    static void setProperty(
            LocalJMeterWorkerRequest request,
            SessionReferenceRegistry registry) throws Exception {
        execute(request, setPatch(request), registry);
    }

    private static MutationResult execute(
            LocalJMeterWorkerRequest request,
            ApplyPatch patch,
            SessionReferenceRegistry registry) throws Exception {
        Path source = Paths.get(request.jmxPath());
        Path jmeterHome = Paths.get(request.jmeterHome());
        Path target = request.targetPath() == null ? null : Paths.get(request.targetPath());
        MutationRequest mutationRequest = target == null
                ? MutationRequest.dryRun(
                        source,
                        jmeterHome,
                        patch,
                        Paths.get(request.dryRunCandidateDirectory()),
                        registry)
                : MutationRequest.commit(
                        source,
                        jmeterHome,
                        patch,
                        target,
                        request.replaceExisting(),
                        registry);
        return new LocalMutationTransaction().execute(mutationRequest);
    }

    private static ApplyPatch setPatch(LocalJMeterWorkerRequest request) throws Exception {
        ApplyPatch.ValueType patchType = ApplyPatch.ValueType.parse(request.propertyType());
        ApplyPatchParser parser = new ApplyPatchParser();
        PropertyAddress address = parser.parsePropertyAddress(request.propertyPath());
        Object value = request.propertyValue();
        if (patchType != ApplyPatch.ValueType.STRING && patchType != ApplyPatch.ValueType.RAW) {
            value = parser.parseStructuredValue(request.propertyValue(), address, patchType);
        }
        ApplyPatch.PropertyChange property = new ApplyPatch.PropertyChange(
                address, value, patchType);
        ApplyPatch.SetOperation set = new ApplyPatch.SetOperation(
                request.locator(), Optional.<String>empty(), Collections.singletonList(property));
        return new ApplyPatch(Collections.singletonList(new ApplyPatch.Change(set)));
    }

    private static String render(MutationResult result) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("appliedCount", Integer.valueOf(result.receipt().appliedCount()));
        data.putAll(result.receipt().auxiliaryResults());
        List<Map<String, String>> created = new ArrayList<Map<String, String>>();
        List<String> deleted = Collections.emptyList();
        if (result.publishedReferences().isPresent()) {
            for (MutationResult.CreatedReference createdReference
                    : result.publishedReferences().get().created()) {
                Map<String, String> row = new LinkedHashMap<String, String>();
                row.put("alias", createdReference.alias());
                row.put("ref", createdReference.publicReference());
                created.add(row);
            }
            deleted = result.publishedReferences().get().deleted();
        }
        data.put("createdRefs", created);
        data.put("deletedRefs", deleted);
        List<Map<String, Object>> changes = new ArrayList<Map<String, Object>>();
        for (MutationChangeResult change : result.receipt().changeResults()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("index", Integer.valueOf(change.index()));
            row.put("operation", change.operation());
            row.put("status", change.status().wireName());
            row.put("context", change.context().toMap());
            if (change.resultRef().isPresent()) {
                row.put("resultRef", change.resultRef().get());
            }
            changes.add(row);
        }
        data.put("changeResults", changes);
        return new Yaml().dump(data);
    }

    static void invalidate(SessionReferenceRegistry registry, DocumentIdentity identity) {
        SessionReferenceRegistry.PreparedState invalidation = registry.prepareInvalidation(identity);
        if (!registry.publish(invalidation)) {
            registry.discard(invalidation);
            throw new IllegalStateException(
                    "Session reference state changed during serialized worker invalidation");
        }
    }

    static String initJmx(LocalJMeterWorkerRequest request) throws Exception {
        Path jmeterHome = Paths.get(request.jmeterHome());
        Path target = Paths.get(request.targetPath());
        JmxTestPlan testPlan = new JmxInitialPlanFactory().create(
                request.testPlanName(), request.threadGroupName());
        Path candidate = targetCandidate(target);
        try {
            LocalJMeterWorkerJmx.save(testPlan, candidate);
            SourceSnapshot<JmxTestPlan> snapshot = sourceSnapshot(candidate, jmeterHome);
            LocalCandidatePreservation.requirePreserved(
                    snapshot.parsed(),
                    MutationImpact.fromSelectedRuntime(
                            testPlan, testPlan.depthFirstTestElements()));
            LocalJMeterFileCommitter.commit(candidate, target, true);
            return new YamlReadRenderer().render(
                    snapshot.parsed(),
                    initReadOptions(request),
                    LocalPropertyGraphRuntimeContext.selected(jmeterHome));
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    static String initJmx(
            LocalJMeterWorkerRequest request,
            SessionReferenceRegistry registry) throws Exception {
        if (registry == null) {
            throw new NullPointerException("registry");
        }
        Path jmeterHome = Paths.get(request.jmeterHome());
        Path target = Paths.get(request.targetPath());
        DocumentIdentity identity = DocumentIdentity.of(jmeterHome, target);
        JmxTestPlan testPlan = new JmxInitialPlanFactory().create(
                request.testPlanName(), request.threadGroupName());
        MutationImpact impact = MutationImpact.fromSelectedRuntime(
                testPlan, testPlan.depthFirstTestElements());
        Path candidate = targetCandidate(target);
        SessionReferenceRegistry.PreparedState prepared = null;
        boolean published = false;
        try {
            LocalJMeterWorkerJmx.save(testPlan, candidate);
            SourceSnapshot<JmxTestPlan> snapshot = sourceSnapshot(candidate, jmeterHome);
            LocalCandidatePreservation.requirePreserved(snapshot.parsed(), impact);
            prepared = registry.prepareReplacement(identity);
            SessionReferenceSpace referenceSpace = new SessionReferenceSpace(
                    prepared, identity, snapshot);
            BoundReferences references = referenceSpace.bind(snapshot.parsed());
            String rendered = new YamlReadRenderer().render(
                    snapshot.parsed(),
                    initReadOptions(request),
                    references,
                    LocalPropertyGraphRuntimeContext.selected(jmeterHome));
            SessionReferenceRegistry.PreparedPublication publication =
                    registry.preparePublication(referenceSpace.prepareSuccessfulUse());
            LocalJMeterFileCommitter.commit(candidate, target, true);
            publication.publish();
            published = true;
            return rendered;
        } catch (SessionReferenceRegistry.CapacityExceededException exception) {
            throw ReferenceFailure.capacityExceeded(exception);
        } finally {
            Files.deleteIfExists(candidate);
            if (prepared != null && !published) {
                registry.discard(prepared);
            }
        }
    }

    static ApplyPatch parsePatch(String patchYaml) throws Exception {
        if (patchYaml == null
                || patchYaml.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "PATCH_INPUT_TOO_LARGE: YAML patch exceeds the 4 MiB maximum.");
        }
        return new ApplyPatchParser().parse(patchYaml);
    }

    private static ReadOptions initReadOptions(LocalJMeterWorkerRequest request) {
        return new ReadOptions(
                false,
                false,
                request.readDepth() == null ? 2 : Integer.valueOf(request.readDepth()),
                null,
                request.readPropertyMode() == null
                        ? ReadOptions.PropertyMode.NONE
                        : ReadOptions.PropertyMode.valueOf(request.readPropertyMode()));
    }

    static SourceSnapshot<JmxTestPlan> sourceSnapshot(
            final Path source,
            final Path jmeterHome) throws IOException {
        return SourceSnapshot.read(source, new SourceSnapshot.Parser<JmxTestPlan>() {
            @Override
            public JmxTestPlan parse(InputStream immutableBytes) throws IOException {
                Path temporary = Files.createTempFile(
                        "jmx-agent-worker-mutation-snapshot-", ".jmx");
                try {
                    Files.copy(immutableBytes, temporary, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        return LocalJMeterWorkerJmx.load(temporary, jmeterHome);
                    } catch (IOException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        throw new IOException(
                                "Unable to parse immutable mutation snapshot", exception);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        });
    }

    static MutationImpact mutationImpact(JmxTestPlan plan, MutationOutcome outcome) {
        List<TestElement> elements = new ArrayList<TestElement>();
        outcome.affectedNodes().forEach(
                handle -> elements.add(ExactNodeHandles.element(handle)));
        return MutationImpact.fromGraph(
                plan, elements, outcome.propertyGraph(), outcome::receipt);
    }

    static Path dryRunCandidate(Path directory) throws Exception {
        if (directory == null) {
            throw new IllegalArgumentException(
                    "Dry-run apply requires a parent-owned candidate directory.");
        }
        Files.createDirectories(directory);
        return Files.createTempFile(directory, "jmx-agent-worker-candidate-", ".jmx");
    }

    static Path targetCandidate(Path target) throws Exception {
        Path directory = target.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            directory = Paths.get(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(directory);
        return Files.createTempFile(directory, "jmx-agent-worker-candidate-", ".jmx");
    }

    static void requireJMeterPlacement(
            Path jmeterHome,
            String component,
            TestElement parent,
            TestElement child) {
        LocalJMeterMenuRegistry.Entry entry =
                LocalJMeterWorkerComponents.requireLocalEntry(jmeterHome, component);
        JMeterTreeNode childNode = new JMeterTreeNode(child, null) {
            private static final long serialVersionUID = 1L;

            @Override
            public java.util.Collection<String> getMenuCategories() {
                return java.util.Collections.singleton(entry.group());
            }
        };
        if (!MenuFactory.canAddTo(
                new JMeterTreeNode(parent, null),
                new JMeterTreeNode[] {childNode})) {
            throw new JMeterPlacementRejectedException(
                    parent.getClass().getName(), child.getClass().getName());
        }
    }
}
