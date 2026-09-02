package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyFailureException;
import io.github.thisccl.j4a.apply.InvalidPlacementException;
import io.github.thisccl.j4a.apply.MutationOutcome;
import io.github.thisccl.j4a.apply.MutationChangeResult;
import io.github.thisccl.j4a.apply.PlacementAnchorConflicts;
import io.github.thisccl.j4a.apply.ResolvedApplyPlan;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.GraphType;
import io.github.thisccl.j4a.jmx.property.MutationReceipt;
import io.github.thisccl.j4a.jmx.property.PropertyGraphDocumentMapper;
import io.github.thisccl.j4a.jmx.property.PropertyWrite;
import io.github.thisccl.j4a.jmx.property.RecursiveValue;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowWriter;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowEvidence;
import io.github.thisccl.j4a.locator.LocatorNode;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

final class LocalMutationProgram {
    private final DefaultJMeterPropertyGraph propertyGraph;
    private final RuntimeContext runtimeContext;
    private final Path jmeterHome;

    LocalMutationProgram(Path jmeterHome) throws java.io.IOException {
        this.propertyGraph = new DefaultJMeterPropertyGraph();
        this.runtimeContext = LocalPropertyGraphRuntimeContext.selected(
                Objects.requireNonNull(jmeterHome, "jmeterHome"));
        this.jmeterHome = jmeterHome;
    }

    MutationOutcome apply(JmxTestPlan testPlan, ResolvedApplyPlan plan) {
        Objects.requireNonNull(testPlan, "testPlan");
        Objects.requireNonNull(plan, "plan");
        requireNoAnchorConflicts(plan);

        List<TestElement> affected = new ArrayList<>();
        Map<TestElement, List<String>> affectedProperties = new IdentityHashMap<>();
        Map<TestElement, MutationReceipt> receipts = new IdentityHashMap<>();
        Map<ResolvedApplyPlan.SymbolSlot, TestElement> boundSymbols = new IdentityHashMap<>();
        List<ResolvedNodeHandle> inputNodes = inputNodes(testPlan.tree());
        Map<TestElement, Boolean> inputNodeIdentities = new IdentityHashMap<>();
        for (ResolvedNodeHandle inputNode : inputNodes) {
            inputNodeIdentities.put(element(inputNode), Boolean.TRUE);
        }
        Map<TestElement, Boolean> deletedInputs = new IdentityHashMap<>();
        PlacementCursor placementCursor = new PlacementCursor();
        List<MutationChangeResult> changeResults = new ArrayList<MutationChangeResult>();
        for (ResolvedApplyPlan.Change change : plan.changes()) {
            ResolvedApplyPlan.Operation operation = change.operation();
            try {
            if (operation instanceof ResolvedApplyPlan.SetOperation) {
                ResolvedApplyPlan.SetOperation set = (ResolvedApplyPlan.SetOperation) operation;
                requireExplicitComponent(set.component());
                TestElement target = requireNode(testPlan.tree(), set.ref(), boundSymbols).element();
                if (!set.properties().isEmpty()) {
                    affected.add(target);
                    recordProperties(affectedProperties, target, set.properties());
                }
                MutationReceipt receipt = applyProperties(target, set.properties());
                if (!set.properties().isEmpty()) {
                    receipts.put(target, receipt);
                }
            } else if (operation instanceof ResolvedApplyPlan.AddOperation) {
                ResolvedApplyPlan.AddOperation add = (ResolvedApplyPlan.AddOperation) operation;
                MutableNode parent = requireNode(testPlan.tree(), add.parent(), boundSymbols);
                Optional<MutableNode> beforeNode = add.before()
                        .map(ref -> requireNode(testPlan.tree(), ref, boundSymbols));
                Optional<MutableNode> afterNode = add.after()
                        .map(ref -> requireNode(testPlan.tree(), ref, boundSymbols));
                requireAddPlacement(parent, beforeNode, afterNode, add.position());
                Optional<TestElement> before = beforeNode.map(MutableNode::element);
                Optional<TestElement> after = afterNode.map(MutableNode::element);
                AddedElement addedResult = applyAdd(
                        parent.element(), parent.childrenTree(), add, before, after, placementCursor);
                TestElement added = addedResult.element;
                if (add.declaredSymbol().isPresent()) {
                    ResolvedApplyPlan.SymbolSlot symbol = add.declaredSymbol().get();
                    if (boundSymbols.put(symbol, added) != null) {
                        throw new IllegalStateException("Resolved symbol slot was bound more than once.");
                    }
                }
                affected.add(added);
                recordProperties(affectedProperties, added, add.properties());
                receipts.put(added, addedResult.receipt);
            } else if (operation instanceof ResolvedApplyPlan.MoveOperation) {
                ResolvedApplyPlan.MoveOperation move = (ResolvedApplyPlan.MoveOperation) operation;
                requireExplicitComponent(move.component());
                MutableNode node = requireNode(testPlan.tree(), move.ref(), boundSymbols);
                MutableNode newParent = requireNode(testPlan.tree(), move.parent(), boundSymbols);
                Optional<MutableNode> beforeNode = move.before()
                        .map(ref -> requireNode(testPlan.tree(), ref, boundSymbols));
                Optional<MutableNode> afterNode = move.after()
                        .map(ref -> requireNode(testPlan.tree(), ref, boundSymbols));
                requireMovePlacement(node, newParent, beforeNode, afterNode, move.position());
                Optional<TestElement> before = beforeNode.map(MutableNode::element);
                Optional<TestElement> after = afterNode.map(MutableNode::element);
                applyMove(node, newParent.childrenTree(), move, before, after, placementCursor);
            } else if (operation instanceof ResolvedApplyPlan.DeleteOperation) {
                ResolvedApplyPlan.DeleteOperation delete = (ResolvedApplyPlan.DeleteOperation) operation;
                requireExplicitComponent(delete.component());
                MutableNode node = requireNode(testPlan.tree(), delete.ref(), boundSymbols);
                collectDeletedInputNodes(node, inputNodeIdentities, deletedInputs);
                node.parentChildrenTree().remove(node.element());
            } else if (operation instanceof ResolvedApplyPlan.AppendOperation) {
                ResolvedApplyPlan.AppendOperation append = (ResolvedApplyPlan.AppendOperation) operation;
                TestElement target = requireNode(testPlan.tree(), append.ref(), boundSymbols).element();
                MutationReceipt receipt = applyAppend(target, append);
                recordIncrementalMutation(
                        affected, affectedProperties, receipts, target, append.property(), receipt);
            } else if (operation instanceof ResolvedApplyPlan.InsertOperation) {
                ResolvedApplyPlan.InsertOperation insert = (ResolvedApplyPlan.InsertOperation) operation;
                TestElement target = requireNode(testPlan.tree(), insert.ref(), boundSymbols).element();
                MutationReceipt receipt = applyInsert(target, insert);
                recordIncrementalMutation(
                        affected, affectedProperties, receipts, target, insert.property(), receipt);
            } else if (operation instanceof ResolvedApplyPlan.RemoveOperation) {
                ResolvedApplyPlan.RemoveOperation remove = (ResolvedApplyPlan.RemoveOperation) operation;
                TestElement target = requireNode(testPlan.tree(), remove.ref(), boundSymbols).element();
                MutationReceipt receipt = applyRemove(target, remove);
                recordIncrementalMutation(
                        affected, affectedProperties, receipts, target, remove.property(), receipt);
            } else {
                throw new IllegalArgumentException("Unsupported resolved apply operation: " + operation.name());
            }
            } catch (RuntimeException exception) {
                exception.addSuppressed(ApplyFailureException.contextOnly(
                        failurePhase(operation, exception), change.changeIndex(),
                        operation.name(), change.context()));
                throw exception;
            }
            changeResults.add(MutationChangeResult.applied(
                    change.changeIndex(), operation.name(), change.context()));
        }

        List<MutationOutcome.CreatedNode> createdNodes = new ArrayList<>();
        for (ResolvedApplyPlan.SymbolSlot symbol : plan.symbolSlots()) {
            TestElement element = boundSymbols.get(symbol);
            if (element != null) {
                createdNodes.add(MutationOutcome.CreatedNode.of(symbol.alias(), ExactNodeHandle.of(element)));
            }
        }
        List<ResolvedNodeHandle> affectedNodes = new ArrayList<>();
        for (TestElement element : affected) {
            affectedNodes.add(ExactNodeHandle.of(element));
        }
        List<ResolvedNodeHandle> deletedNodes = new ArrayList<>();
        for (ResolvedNodeHandle inputNode : inputNodes) {
            if (deletedInputs.containsKey(element(inputNode))) {
                deletedNodes.add(inputNode);
            }
        }
        return MutationOutcome.executed(
                plan.changes().size(),
                createdNodes,
                deletedNodes,
                Collections.<String, Object>emptyMap(),
                affectedNodes,
                affectedProperties,
                propertyGraph,
                receipts,
                changeResults);
    }

    private static String failurePhase(ResolvedApplyPlan.Operation operation, RuntimeException exception) {
        if (exception instanceof InvalidPlacementException) return "placement";
        if (exception instanceof io.github.thisccl.j4a.locator.LocatorNotFoundException) return "locator";
        if (operation instanceof ResolvedApplyPlan.AddOperation) return "materialization";
        if (operation instanceof ResolvedApplyPlan.MoveOperation) return "placement";
        return "property";
    }

    private void requireExplicitComponent(Optional<String> component) {
        component.ifPresent(value -> LocalJMeterWorkerComponents.requireLocalEntry(jmeterHome, value));
    }

    private static void requireNoAnchorConflicts(ResolvedApplyPlan plan) {
        PlacementAnchorConflicts<Object> anchorConflicts = new PlacementAnchorConflicts<>();
        for (ResolvedApplyPlan.Change change : plan.changes()) {
            ResolvedApplyPlan.Operation operation = change.operation();
            if (operation instanceof ResolvedApplyPlan.AddOperation) {
                ResolvedApplyPlan.AddOperation add = (ResolvedApplyPlan.AddOperation) operation;
                anchorConflicts.recordAnchor(add.before().map(LocalMutationProgram::referenceIdentity));
                anchorConflicts.recordAnchor(add.after().map(LocalMutationProgram::referenceIdentity));
            } else if (operation instanceof ResolvedApplyPlan.MoveOperation) {
                ResolvedApplyPlan.MoveOperation move = (ResolvedApplyPlan.MoveOperation) operation;
                anchorConflicts.recordAnchor(move.before()
                        .filter(ref -> !referenceIdentity(move.ref()).equals(referenceIdentity(ref)))
                        .map(LocalMutationProgram::referenceIdentity));
                anchorConflicts.recordAnchor(move.after()
                        .filter(ref -> !referenceIdentity(move.ref()).equals(referenceIdentity(ref)))
                        .map(LocalMutationProgram::referenceIdentity));
                anchorConflicts.recordRelocated(referenceIdentity(move.ref()));
            } else if (operation instanceof ResolvedApplyPlan.DeleteOperation) {
                ResolvedApplyPlan.DeleteOperation delete = (ResolvedApplyPlan.DeleteOperation) operation;
                anchorConflicts.recordRelocated(referenceIdentity(delete.ref()));
            }
        }
        if (anchorConflicts.conflictingAnchor().isPresent()) {
            throw new InvalidPlacementException("Placement anchor cannot be moved or deleted in the same patch.");
        }
    }

    private static Object referenceIdentity(ResolvedApplyPlan.NodeReference reference) {
        if (reference instanceof ResolvedApplyPlan.InputNodeReference) {
            return element(((ResolvedApplyPlan.InputNodeReference) reference).handle());
        }
        if (reference instanceof ResolvedApplyPlan.SymbolReference) {
            return ((ResolvedApplyPlan.SymbolReference) reference).symbol();
        }
        throw new IllegalArgumentException("Unsupported resolved node reference.");
    }

    private static TestElement element(ResolvedNodeHandle handle) {
        if (!(handle instanceof ExactNodeHandle)) {
            throw new IllegalArgumentException("Resolved node handle is not owned by this reference module.");
        }
        return ((ExactNodeHandle) handle).element();
    }

    private static void requireAddPlacement(
            MutableNode parent, Optional<MutableNode> before, Optional<MutableNode> after,
            Optional<String> position) {
        int selectors = selectorCount(before, after, position);
        if (selectors != 1) {
            throw new InvalidPlacementException("Use exactly one placement selector: before, after, or position.");
        }
        requireSibling(parent, before, "before");
        requireSibling(parent, after, "after");
        position.ifPresent(value -> requirePosition(value, parent.childrenTree().size()));
    }

    private static void requireMovePlacement(
            MutableNode node, MutableNode parent, Optional<MutableNode> before,
            Optional<MutableNode> after, Optional<String> position) {
        if (selectorCount(before, after, position) != 1) {
            throw new InvalidPlacementException("Use exactly one placement selector: before, after, or position.");
        }
        if (node.element() == parent.element()) {
            throw new InvalidPlacementException("A component cannot be moved under itself.");
        }
        if (contains(node, parent)) {
            throw new InvalidPlacementException("A component cannot be moved under its own descendant.");
        }
        if (before.filter(value -> value.element() == node.element()).isPresent()) {
            throw new InvalidPlacementException("A component cannot be placed before itself.");
        }
        if (after.filter(value -> value.element() == node.element()).isPresent()) {
            throw new InvalidPlacementException("A component cannot be placed after itself.");
        }
        if (before.filter(value -> contains(node, value)).isPresent()) {
            throw new InvalidPlacementException("A component cannot be placed before its own descendant.");
        }
        if (after.filter(value -> contains(node, value)).isPresent()) {
            throw new InvalidPlacementException("A component cannot be placed after its own descendant.");
        }
        requireSibling(parent, before, "before");
        requireSibling(parent, after, "after");
        position.ifPresent(value -> requirePosition(value, parent.childrenTree().size()));
    }

    private static int selectorCount(
            Optional<?> before, Optional<?> after, Optional<String> position) {
        return (before.isPresent() ? 1 : 0) + (after.isPresent() ? 1 : 0) + (position.isPresent() ? 1 : 0);
    }

    private static void requireSibling(MutableNode parent, Optional<MutableNode> sibling, String field) {
        if (sibling.isPresent() && sibling.get().parentChildrenTree() != parent.childrenTree()) {
            throw new InvalidPlacementException("Placement " + field + " ref is not a child of the requested parent.");
        }
    }

    private static void requirePosition(String position, int childCount) {
        if ("first".equals(position) || "last".equals(position)) {
            return;
        }
        try {
            int index = Integer.parseInt(position);
            if (index >= 0 && index <= childCount) {
                return;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new InvalidPlacementException(
                "Position must be first, last, or an insertion index from 0 to " + childCount + ".");
    }

    private static boolean contains(MutableNode root, MutableNode candidate) {
        if (root.element() == candidate.element()) {
            return true;
        }
        for (LocatorNode child : root.children()) {
            if (contains((MutableNode) child, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static void recordProperties(
            Map<TestElement, List<String>> propertiesByElement,
            TestElement element,
            List<ApplyPatch.PropertyChange> properties) {
        List<String> names = propertiesByElement.computeIfAbsent(element, ignored -> new ArrayList<>());
        for (ApplyPatch.PropertyChange property : properties) {
            String name = String.valueOf(property.property().segments().get(0));
            if (!names.contains(name)) names.add(name);
        }
    }

    private AddedElement applyAdd(
            TestElement parent, HashTree parentChildrenTree,
            ResolvedApplyPlan.AddOperation add,
            Optional<TestElement> before,
            Optional<TestElement> after,
            PlacementCursor placementCursor) {
        LocalJMeterMenuRegistry.Entry entry =
                LocalJMeterWorkerComponents.requireLocalEntry(jmeterHome, add.component());
        if (!entry.addEnabled()) {
            throw new JMeterAddDisabledException(add.component());
        }
        TestElement element = LocalJMeterElementMaterializer.create(entry);
        MutationReceipt receipt = applyProperties(element, add.properties());
        requirePlacement(add.component(), parent, element);

        HashTree newSubtree = parentChildrenTree.add(element);
        placeChild(parentChildrenTree, element, newSubtree, before, after, add.position(), placementCursor);
        return new AddedElement(element, receipt);
    }

    private MutationReceipt applyProperties(
            TestElement element,
            List<ApplyPatch.PropertyChange> properties) {
        return applyProperties(element, properties, null);
    }

    private MutationReceipt applyProperties(
            TestElement element,
            List<ApplyPatch.PropertyChange> properties,
            LocalJMeterGuiSemanticMetadata.Observation semanticMetadata) {
        GraphSnapshot snapshot = propertyGraph.inspect(element, runtimeContext);
        List<PropertyWrite> writes = new ArrayList<PropertyWrite>(properties.size());
        for (ApplyPatch.PropertyChange property : properties) {
            GraphNode node = snapshot.resolve(property.property());
            PropertyPath path = node.path();
            writes.add(decodePropertyWrite(element, node, path, property, semanticMetadata));
        }
        return propertyGraph.apply(element, snapshot, writes);
    }

    private MutationReceipt applyAppend(
            TestElement element, ResolvedApplyPlan.AppendOperation append) {
        GraphSnapshot snapshot = propertyGraph.inspect(element, runtimeContext);
        GraphNode node = snapshot.resolve(append.property());
        RuntimeStructuredRowEvidence evidence =
                correlatedEvidence(element, node.path()).orElse(null);
        PropertyWrite write = new RuntimeStructuredRowWriter()
                .prepareAppend(element, node.path(), append.row(), runtimeContext, evidence)
                .orElseThrow(() -> unsupportedRows(append.property()));
        return propertyGraph.apply(element, snapshot, Collections.singletonList(write));
    }

    private MutationReceipt applyInsert(
            TestElement element, ResolvedApplyPlan.InsertOperation insert) {
        GraphSnapshot snapshot = propertyGraph.inspect(element, runtimeContext);
        GraphNode node = snapshot.resolve(insert.property());
        RuntimeStructuredRowEvidence evidence =
                correlatedEvidence(element, node.path()).orElse(null);
        PropertyWrite write = new RuntimeStructuredRowWriter()
                .prepareInsert(element, node.path(), insert.index(), insert.row(), runtimeContext, evidence)
                .orElseThrow(() -> unsupportedRows(insert.property()));
        return propertyGraph.apply(element, snapshot, Collections.singletonList(write));
    }

    private MutationReceipt applyRemove(
            TestElement element, ResolvedApplyPlan.RemoveOperation remove) {
        GraphSnapshot snapshot = propertyGraph.inspect(element, runtimeContext);
        GraphNode node = snapshot.resolve(remove.property());
        PropertyWrite write = new RuntimeStructuredRowWriter()
                .prepareRemove(element, node.path(), remove.index(), runtimeContext)
                .orElseThrow(() -> unsupportedRows(remove.property()));
        return propertyGraph.apply(element, snapshot, Collections.singletonList(write));
    }

    private static IllegalArgumentException unsupportedRows(PropertyAddress property) {
        return new IllegalArgumentException(
                "Property '" + property
                        + "' does not expose a runtime-proven rows representation; "
                        + "copy its generic or opaque value from focused read when available");
    }

    private static void recordIncrementalMutation(
            List<TestElement> affected,
            Map<TestElement, List<String>> affectedProperties,
            Map<TestElement, MutationReceipt> receipts,
            TestElement target,
            PropertyAddress property,
            MutationReceipt receipt) {
        affected.add(target);
        String name = String.valueOf(property.segments().get(0));
        List<String> names = affectedProperties.computeIfAbsent(
                target, ignored -> new ArrayList<String>());
        if (!names.contains(name)) {
            names.add(name);
        }
        receipts.put(target, receipt);
    }

    private PropertyWrite decodePropertyWrite(
            TestElement element,
            GraphNode node,
            PropertyPath path,
            ApplyPatch.PropertyChange property,
            LocalJMeterGuiSemanticMetadata.Observation semanticMetadata) {
        if (property.type() == ApplyPatch.ValueType.ROWS) {
            RuntimeStructuredRowEvidence evidence =
                    correlatedEvidence(element, path, semanticMetadata).orElse(null);
            return new RuntimeStructuredRowWriter()
                    .prepare(element, path, property.value(), runtimeContext, evidence)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Property '" + propertyLabel(property)
                                    + "' does not expose a runtime-proven rows representation; "
                                    + "copy its generic or opaque value from focused read when available"));
        }
        if (property.type() == ApplyPatch.ValueType.NULL && property.value() == null) {
            if (node.type() != GraphType.NULL) {
                throw representationMismatch(property);
            }
            return new PropertyWrite(
                    path, GraphType.NULL,
                    RecursiveValue.presentNull(node.value().propertyClass()));
        }
        if (property.type().recursiveGraph()
                || property.type().graphScalar() && property.value() instanceof Map<?, ?>) {
            Map<String, Object> document = new java.util.LinkedHashMap<String, Object>();
            document.put("property", io.github.thisccl.j4a.path.PropertyAddress.fromPath(path).segments());
            document.put("type", property.type().name().toLowerCase(java.util.Locale.ROOT));
            document.put("value", property.value());
            io.github.thisccl.j4a.jmx.property.PropertyGraphDocument decoded =
                    new PropertyGraphDocumentMapper().fromDocument(document);
            if (decoded.type() != node.type()
                    || !decoded.value().propertyClass().equals(node.value().propertyClass())) {
                throw representationMismatch(property);
            }
            return new PropertyWrite(path, decoded.type(), decoded.value());
        }
        requireLegacyScalarType(node.type(), property.type(), propertyLabel(property));
        Object value = property.value();
        switch (node.type()) {
            case STRING:
            case BOOLEAN:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
                break;
            default:
                throw new IllegalArgumentException(
                        "Property '" + property.property()
                                + "' requires a recursive or semantic representation");
        }
        return new PropertyWrite(
                path,
                node.type(),
                RecursiveValue.scalar(node.type(), node.value().propertyClass(), value));
    }

    private Optional<RuntimeStructuredRowEvidence> correlatedEvidence(
            TestElement element, PropertyPath path) {
        return correlatedEvidence(element, path, null);
    }

    private Optional<RuntimeStructuredRowEvidence> correlatedEvidence(
            TestElement element,
            PropertyPath path,
            LocalJMeterGuiSemanticMetadata.Observation suppliedMetadata) {
        if (path.segments().size() != 1) {
            return Optional.empty();
        }
        LocalJMeterGuiSemanticMetadata.Observation metadata = suppliedMetadata;
        if (metadata == null) {
            String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
            String resolvedGuiClass = org.apache.jmeter.save.SaveService.aliasToClass(guiClass);
            LocalJMeterMenuRegistry.Entry entry = LocalJMeterMenuRegistry.current()
                    .resolve(resolvedGuiClass == null ? guiClass : resolvedGuiClass).orElse(null);
            if (entry == null) {
                return Optional.empty();
            }
            metadata = LocalComponentDiscovery.semanticMetadata(entry, runtimeContext);
        }
        String property = path.segments().get(0).name();
        return LocalStructuredRowEvidenceResolver.resolve(property, metadata);
    }

    private static IllegalArgumentException representationMismatch(ApplyPatch.PropertyChange property) {
        return new IllegalArgumentException(
                "Property '" + property.property()
                        + "' representation does not match the observed graph node");
    }

    private static String propertyLabel(ApplyPatch.PropertyChange property) {
        return property.property().toString();
    }

    private static void requireLegacyScalarType(
            GraphType observed, ApplyPatch.ValueType requested, String property) {
        boolean compatible;
        switch (observed) {
            case STRING:
                compatible = requested == ApplyPatch.ValueType.STRING;
                break;
            case BOOLEAN:
                compatible = requested == ApplyPatch.ValueType.BOOLEAN;
                break;
            case INT:
                compatible = requested == ApplyPatch.ValueType.INT;
                break;
            case LONG:
                compatible = requested == ApplyPatch.ValueType.LONG;
                break;
            case FLOAT:
                compatible = requested == ApplyPatch.ValueType.FLOAT;
                break;
            case DOUBLE:
                compatible = requested == ApplyPatch.ValueType.DOUBLE;
                break;
            default:
                compatible = false;
        }
        if (!compatible) {
            throw new IllegalArgumentException(
                    "Property '" + property + "' expected type '" + observed.wireName()
                            + "' but received type '" + requested.name().toLowerCase(java.util.Locale.ROOT)
                            + "' at address " + property);
        }
    }

    private void applyMove(
            MutableNode node,
            HashTree newParentChildrenTree,
            ResolvedApplyPlan.MoveOperation move,
            Optional<TestElement> before,
            Optional<TestElement> after,
            PlacementCursor placementCursor) {
        HashTree movedSubtree = node.parentChildrenTree().remove(node.element());
        newParentChildrenTree.set(node.element(), movedSubtree);
        placeChild(newParentChildrenTree, node.element(), movedSubtree, before, after, move.position(), placementCursor);
    }

    private void placeChild(
            HashTree parentChildrenTree,
            TestElement placed,
            HashTree placedSubtree,
            Optional<TestElement> before,
            Optional<TestElement> after,
            Optional<String> position,
            PlacementCursor placementCursor) {
        List<Map.Entry<Object, HashTree>> entries = orderedEntries(parentChildrenTree);
        entries.removeIf(entry -> entry.getKey() == placed);
        PlacementKey placementKey = new PlacementKey(
                parentChildrenTree, before.orElseGet(() -> after.orElse(null)),
                before.isPresent() ? "before" : after.isPresent() ? "after" : position.orElse("last"));
        TestElement previous = placementCursor.previous(placementKey);
        int index;
        if (previous != null) {
            index = indexOf(entries, previous) + 1;
        } else if (before.isPresent()) {
            index = indexOf(entries, before.get());
        } else if (after.isPresent()) {
            index = indexOf(entries, after.get()) + 1;
        } else if (position.isPresent()) {
            index = insertionIndex(position.get(), entries.size());
        } else {
            index = entries.size();
        }

        entries.add(index, new AbstractMap.SimpleImmutableEntry<>(placed, placedSubtree));
        parentChildrenTree.clear();
        for (Map.Entry<Object, HashTree> entry : entries) {
            parentChildrenTree.set(entry.getKey(), entry.getValue());
        }
        placementCursor.record(placementKey, placed);
    }

    private List<Map.Entry<Object, HashTree>> orderedEntries(HashTree tree) {
        List<Map.Entry<Object, HashTree>> entries = new ArrayList<>();
        for (Object key : tree.list()) {
            entries.add(new AbstractMap.SimpleImmutableEntry<>(key, tree.getTree(key)));
        }
        return entries;
    }

    private int indexOf(List<Map.Entry<Object, HashTree>> entries, TestElement element) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).getKey() == element) {
                return index;
            }
        }
        throw new IllegalArgumentException("Placement ref is not in the target parent.");
    }

    private int insertionIndex(String position, int childCount) {
        if ("first".equals(position)) {
            return 0;
        }
        if ("last".equals(position)) {
            return childCount;
        }
        return Integer.parseInt(position);
    }

    private MutableNode requireNode(
            HashTree tree,
            ResolvedApplyPlan.NodeReference reference,
            Map<ResolvedApplyPlan.SymbolSlot, TestElement> boundSymbols) {
        TestElement resolved = resolveElement(reference, boundSymbols);
        for (MutableNode root : mutableRoots(tree, null)) {
            MutableNode node = findNode(root, resolved);
            if (node != null) {
                return node;
            }
        }
        throw new IllegalArgumentException("Resolved node handle is not present in the current candidate tree.");
    }

    private static TestElement resolveElement(
            ResolvedApplyPlan.NodeReference reference,
            Map<ResolvedApplyPlan.SymbolSlot, TestElement> boundSymbols) {
        if (reference instanceof ResolvedApplyPlan.InputNodeReference) {
            return element(((ResolvedApplyPlan.InputNodeReference) reference).handle());
        }
        if (reference instanceof ResolvedApplyPlan.SymbolReference) {
            ResolvedApplyPlan.SymbolSlot symbol = ((ResolvedApplyPlan.SymbolReference) reference).symbol();
            TestElement element = boundSymbols.get(symbol);
            if (element != null) {
                return element;
            }
            throw new IllegalStateException("Resolved symbol slot is not bound yet: " + symbol.alias());
        }
        throw new IllegalArgumentException("Unsupported resolved node reference.");
    }

    private MutableNode findNode(MutableNode root, TestElement resolved) {
        if (root.element() == resolved) {
            return root;
        }
        for (LocatorNode child : root.children()) {
            MutableNode match = findNode((MutableNode) child, resolved);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private List<MutableNode> mutableRoots(HashTree tree, HashTree parentChildrenTree) {
        List<MutableNode> roots = new ArrayList<>();
        for (Object node : tree.list()) {
            if (node instanceof TestElement) {
                TestElement testElement = (TestElement) node;
                HashTree childrenTree = tree.getTree(node);
                roots.add(new MutableNode(testElement, parentChildrenTree == null ? tree : parentChildrenTree, childrenTree));
            } else {
                roots.addAll(mutableRoots(tree.getTree(node), tree));
            }
        }
        return roots;
    }

    private static List<ResolvedNodeHandle> inputNodes(HashTree tree) {
        List<ResolvedNodeHandle> inputNodes = new ArrayList<>();
        collectInputNodes(tree, inputNodes);
        return inputNodes;
    }

    private static void collectInputNodes(HashTree tree, List<ResolvedNodeHandle> inputNodes) {
        for (Object node : tree.list()) {
            if (node instanceof TestElement) {
                TestElement element = (TestElement) node;
                inputNodes.add(ExactNodeHandle.of(element));
            }
            collectInputNodes(tree.getTree(node), inputNodes);
        }
    }

    private static void collectDeletedInputNodes(
            MutableNode node,
            Map<TestElement, Boolean> inputNodes,
            Map<TestElement, Boolean> deletedInputs) {
        if (inputNodes.containsKey(node.element())) {
            deletedInputs.put(node.element(), Boolean.TRUE);
        }
        for (LocatorNode child : node.children()) {
            collectDeletedInputNodes((MutableNode) child, inputNodes, deletedInputs);
        }
    }

    private static final class PlacementCursor {
        private final Map<PlacementKey, TestElement> previousByPlacement = new java.util.HashMap<>();

        private TestElement previous(PlacementKey placement) {
            return previousByPlacement.get(placement);
        }

        private void record(PlacementKey placement, TestElement element) {
            previousByPlacement.put(placement, element);
        }
    }

    private static final class PlacementKey {
        private final HashTree parent;
        private final TestElement anchor;
        private final String placement;

        private PlacementKey(HashTree parent, TestElement anchor, String placement) {
            this.parent = parent;
            this.anchor = anchor;
            this.placement = placement;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PlacementKey)) {
                return false;
            }
            PlacementKey that = (PlacementKey) other;
            return parent == that.parent && anchor == that.anchor && placement.equals(that.placement);
        }

        @Override
        public int hashCode() {
            int result = 31 * System.identityHashCode(parent) + System.identityHashCode(anchor);
            return 31 * result + placement.hashCode();
        }
    }

    private void requirePlacement(
            String component, TestElement parent, TestElement child) {
        LocalJMeterWorkerMutations.requireJMeterPlacement(
                jmeterHome, component, parent, child);
    }

    private static final class AddedElement {
        private final TestElement element;
        private final MutationReceipt receipt;

        private AddedElement(TestElement element, MutationReceipt receipt) {
            this.element = element;
            this.receipt = receipt;
        }
    }

    private static final class MutableNode implements LocatorNode {
        private final TestElement element;
        private final HashTree parentChildrenTree;
        private final HashTree childrenTree;

        private MutableNode(TestElement element, HashTree parentChildrenTree, HashTree childrenTree) {
            this.element = element;
            this.parentChildrenTree = parentChildrenTree;
            this.childrenTree = childrenTree;
        }

        private TestElement element() {
            return element;
        }

        private HashTree parentChildrenTree() {
            return parentChildrenTree;
        }

        private HashTree childrenTree() {
            return childrenTree;
        }

        @Override
        public String componentClass() {
            return element.getClass().getSimpleName();
        }

        @Override
        public List<? extends LocatorNode> children() {
            List<MutableNode> children = new ArrayList<>();
            for (Object child : childrenTree.list()) {
                if (child instanceof TestElement) {
                    TestElement testElement = (TestElement) child;
                    children.add(new MutableNode(testElement, childrenTree, childrenTree.getTree(child)));
                }
            }
            return Collections.unmodifiableList(children);
        }

        @Override
        public String displayName() {
            return element.getName();
        }
    }
}
