package io.github.thisccl.j4a.apply;

import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.MutationReceipt;
import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;

/**
 * Immutable transport-neutral facts produced by the loaded-plan mutation engine.
 */
public final class MutationOutcome {
    private final int appliedCount;
    private final List<CreatedNode> createdNodes;
    private final List<ResolvedNodeHandle> deletedNodes;
    private final Map<String, Object> auxiliaryResults;
    private final List<ResolvedNodeHandle> affectedNodes;
    private final Map<TestElement, List<String>> affectedProperties;
    private final DefaultJMeterPropertyGraph propertyGraph;
    private final Map<TestElement, MutationReceipt> mutationReceipts;
    private final List<MutationChangeResult> changeResults;

    private MutationOutcome(
            int appliedCount,
            List<CreatedNode> createdNodes,
            List<ResolvedNodeHandle> deletedNodes,
            Map<String, ?> auxiliaryResults,
            List<ResolvedNodeHandle> affectedNodes,
            Map<TestElement, List<String>> affectedProperties,
            DefaultJMeterPropertyGraph propertyGraph,
            Map<TestElement, MutationReceipt> mutationReceipts,
            List<MutationChangeResult> changeResults) {
        if (appliedCount < 0) {
            throw new IllegalArgumentException("appliedCount must not be negative");
        }
        this.appliedCount = appliedCount;
        this.createdNodes = immutableCopy(createdNodes, "createdNodes");
        this.deletedNodes = immutableCopy(deletedNodes, "deletedNodes");
        this.auxiliaryResults = immutableMap(auxiliaryResults);
        this.affectedNodes = immutableCopy(affectedNodes, "affectedNodes");
        this.affectedProperties = immutableIdentityMap(affectedProperties);
        this.propertyGraph = propertyGraph;
        this.mutationReceipts = immutableReceiptMap(mutationReceipts);
        this.changeResults = immutableCopy(changeResults, "changeResults");
    }

    public static MutationOutcome applied(int appliedCount) {
        return new MutationOutcome(
                appliedCount,
                Collections.<CreatedNode>emptyList(),
                Collections.<ResolvedNodeHandle>emptyList(),
                Collections.<String, Object>emptyMap(),
                Collections.<ResolvedNodeHandle>emptyList(),
                Collections.<TestElement, List<String>>emptyMap(),
                null,
                Collections.<TestElement, MutationReceipt>emptyMap(),
                Collections.<MutationChangeResult>emptyList());
    }

    public static MutationOutcome of(
            int appliedCount,
            List<CreatedNode> createdNodes,
            List<ResolvedNodeHandle> deletedNodes,
            Map<String, ?> auxiliaryResults) {
        return new MutationOutcome(
                appliedCount,
                createdNodes,
                deletedNodes,
                auxiliaryResults,
                Collections.<ResolvedNodeHandle>emptyList(),
                Collections.<TestElement, List<String>>emptyMap(),
                null,
                Collections.<TestElement, MutationReceipt>emptyMap(),
                Collections.<MutationChangeResult>emptyList());
    }

    public static MutationOutcome executed(
            int appliedCount,
            List<CreatedNode> createdNodes,
            List<ResolvedNodeHandle> deletedNodes,
            Map<String, ?> auxiliaryResults,
            List<ResolvedNodeHandle> affectedNodes,
            Map<TestElement, List<String>> affectedProperties) {
        return new MutationOutcome(
                appliedCount, createdNodes, deletedNodes, auxiliaryResults, affectedNodes, affectedProperties,
                null, Collections.<TestElement, MutationReceipt>emptyMap(),
                Collections.<MutationChangeResult>emptyList());
    }

    public static MutationOutcome executed(
            int appliedCount,
            List<CreatedNode> createdNodes,
            List<ResolvedNodeHandle> deletedNodes,
            Map<String, ?> auxiliaryResults,
            List<ResolvedNodeHandle> affectedNodes,
            Map<TestElement, List<String>> affectedProperties,
            DefaultJMeterPropertyGraph propertyGraph,
            Map<TestElement, MutationReceipt> mutationReceipts,
            List<MutationChangeResult> changeResults) {
        return new MutationOutcome(
                appliedCount, createdNodes, deletedNodes, auxiliaryResults, affectedNodes, affectedProperties,
                Objects.requireNonNull(propertyGraph, "propertyGraph"), mutationReceipts, changeResults);
    }

    public int appliedCount() {
        return appliedCount;
    }

    public List<CreatedNode> createdNodes() {
        return createdNodes;
    }

    public List<ResolvedNodeHandle> deletedNodes() {
        return deletedNodes;
    }

    public Map<String, Object> auxiliaryResults() {
        return auxiliaryResults;
    }

    public List<ResolvedNodeHandle> affectedNodes() {
        return affectedNodes;
    }

    public List<String> affectedProperties(TestElement element) {
        List<String> properties = affectedProperties.get(Objects.requireNonNull(element, "element"));
        return properties == null ? Collections.<String>emptyList() : properties;
    }

    public DefaultJMeterPropertyGraph propertyGraph() {
        return propertyGraph;
    }

    public MutationReceipt receipt(TestElement element) {
        return mutationReceipts.get(Objects.requireNonNull(element, "element"));
    }

    public List<MutationChangeResult> changeResults() {
        return changeResults;
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        List<T> copy = new ArrayList<T>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> values) {
        Objects.requireNonNull(values, "auxiliaryResults");
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "auxiliary result name"), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<TestElement, List<String>> immutableIdentityMap(
            Map<TestElement, List<String>> values) {
        Objects.requireNonNull(values, "affectedProperties");
        Map<TestElement, List<String>> copy = new java.util.IdentityHashMap<TestElement, List<String>>();
        for (Map.Entry<TestElement, List<String>> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "affected element"),
                    immutableCopy(entry.getValue(), "affected property names"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<TestElement, MutationReceipt> immutableReceiptMap(
            Map<TestElement, MutationReceipt> values) {
        Objects.requireNonNull(values, "mutationReceipts");
        Map<TestElement, MutationReceipt> copy = new java.util.IdentityHashMap<TestElement, MutationReceipt>();
        for (Map.Entry<TestElement, MutationReceipt> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "affected element"),
                    Objects.requireNonNull(entry.getValue(), "mutation receipt"));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static final class CreatedNode {
        private final String alias;
        private final ResolvedNodeHandle handle;

        private CreatedNode(String alias, ResolvedNodeHandle handle) {
            this.alias = Objects.requireNonNull(alias, "alias");
            this.handle = Objects.requireNonNull(handle, "handle");
        }

        public static CreatedNode of(String alias, ResolvedNodeHandle handle) {
            return new CreatedNode(alias, handle);
        }

        public String alias() {
            return alias;
        }

        public ResolvedNodeHandle handle() {
            return handle;
        }
    }
}
