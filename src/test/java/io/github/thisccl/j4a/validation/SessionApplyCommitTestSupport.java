package io.github.thisccl.j4a.validation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class SessionApplyCommitTestSupport {
    static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    static LocalJMeterWorkerOperations operations(SessionReferenceRegistry registry) {
        return LocalJMeterWorkerOperations.session(
                registry, new LocalJMeterWorkerOrderCapability(() -> { }));
    }

    static LocalJMeterWorkerRequest read(Path source, String reference) {
        return LocalJMeterWorkerRequest.renderReadData(
                source, jmeterHome(), "8", reference, "NONE", "false");
    }

    static String mixedPatch(List<String> refs) {
        return "changes:\n"
                + "  - set:\n      ref: " + refs.get(0) + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: Mixed Plan\n          type: string\n"
                + "  - add:\n      parent: " + refs.get(1) + "\n      position: last\n"
                + "      component: org.apache.jmeter.control.gui.LogicControllerGui\n"
                + "      as: controller\n"
                + "  - move:\n      ref: " + refs.get(2) + "\n      parent: $controller\n      position: last\n"
                + "  - add:\n      parent: $controller\n      position: first\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      as: child\n"
                + "  - move:\n      ref: $child\n      parent: $controller\n      position: last\n"
                + "  - add:\n      parent: $controller\n      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "  - add:\n      parent: " + refs.get(1) + "\n      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      as: temporary\n"
                + "  - delete:\n      ref: $temporary\n";
    }

    static String fingerprint(Path path) throws Exception {
        return SourceSnapshot.read(path, ignored -> null).fingerprint();
    }

    static List<String> references(String rendered) {
        List<String> values = new ArrayList<String>();
        collectReferences(new org.yaml.snakeyaml.Yaml().load(rendered), values);
        return values;
    }

    private static void collectReferences(Object value, List<String> references) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("ref".equals(entry.getKey()) && entry.getValue() instanceof String) {
                    references.add((String) entry.getValue());
                }
                collectReferences(entry.getValue(), references);
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectReferences(item, references);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mappings(Object value) {
        return (List<Map<String, Object>>) value;
    }

    static Object registrySnapshot(SessionReferenceRegistry registry) throws Exception {
        java.lang.reflect.Method snapshot = SessionReferenceRegistry.class.getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);
        return snapshot.invoke(registry);
    }

    static java.util.Set<String> registrySnapshotTokens(
            SessionReferenceRegistry registry, DocumentIdentity identity) throws Exception {
        Object state = registrySnapshot(registry);
        java.lang.reflect.Method tokens = state.getClass().getDeclaredMethod("tokens", DocumentIdentity.class);
        tokens.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> values = (java.util.Set<String>) tokens.invoke(state, identity);
        return values;
    }

    static List<DocumentIdentity> registrySnapshotLru(SessionReferenceRegistry registry) throws Exception {
        Object state = registrySnapshot(registry);
        java.lang.reflect.Method identities = state.getClass().getDeclaredMethod("identitiesInLruOrder");
        identities.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<DocumentIdentity> values = (List<DocumentIdentity>) identities.invoke(state);
        return values;
    }

    enum FailureStage {
        CANDIDATE_PREPARATION,
        RECONCILIATION,
        RECEIPT_PREPARATION,
        PUBLICATION_PREPARATION,
        PAYLOAD_SHAPING,
        COMMIT
    }
}
