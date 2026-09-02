package io.github.thisccl.j4a.apply;

import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ResolvedApplyPlan {
    private final List<Change> changes;
    private final List<SymbolSlot> symbolSlots;

    ResolvedApplyPlan(List<Change> changes, List<SymbolSlot> symbolSlots) {
        this.changes = immutableCopy(changes, "changes");
        this.symbolSlots = immutableCopy(symbolSlots, "symbolSlots");
    }

    public List<Change> changes() {
        return changes;
    }

    public List<SymbolSlot> symbolSlots() {
        return symbolSlots;
    }

    public static final class Change {
        private final int changeIndex;
        private final Operation operation;
        private final MutationChangeContext context;

        Change(int changeIndex, Operation operation, MutationChangeContext context) {
            if (changeIndex < 0) {
                throw new IllegalArgumentException("changeIndex must not be negative");
            }
            this.changeIndex = changeIndex;
            this.operation = Objects.requireNonNull(operation, "operation");
            this.context = Objects.requireNonNull(context, "context");
        }

        public int changeIndex() {
            return changeIndex;
        }

        public Operation operation() {
            return operation;
        }

        public MutationChangeContext context() {
            return context;
        }
    }

    public interface Operation {
        String name();
    }

    public interface NodeReference {
    }

    public static final class InputNodeReference implements NodeReference {
        private final ResolvedNodeHandle handle;

        InputNodeReference(ResolvedNodeHandle handle) {
            this.handle = Objects.requireNonNull(handle, "handle");
        }

        public ResolvedNodeHandle handle() {
            return handle;
        }
    }

    public static final class SymbolReference implements NodeReference {
        private final SymbolSlot symbol;

        SymbolReference(SymbolSlot symbol) {
            this.symbol = Objects.requireNonNull(symbol, "symbol");
        }

        public SymbolSlot symbol() {
            return symbol;
        }
    }

    public static final class SymbolSlot {
        private final String alias;
        private final int declarationOrder;
        private final int changeIndex;

        SymbolSlot(String alias, int declarationOrder, int changeIndex) {
            this.alias = Objects.requireNonNull(alias, "alias");
            if (declarationOrder < 0) {
                throw new IllegalArgumentException("declarationOrder must not be negative");
            }
            if (changeIndex < 0) {
                throw new IllegalArgumentException("changeIndex must not be negative");
            }
            this.declarationOrder = declarationOrder;
            this.changeIndex = changeIndex;
        }

        public String alias() {
            return alias;
        }

        public int declarationOrder() {
            return declarationOrder;
        }

        public int changeIndex() {
            return changeIndex;
        }
    }

    public static final class SetOperation implements Operation {
        private final NodeReference ref;
        private final Optional<String> component;
        private final List<ApplyPatch.PropertyChange> properties;

        SetOperation(
                NodeReference ref,
                Optional<String> component,
                List<ApplyPatch.PropertyChange> properties) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.component = optional(component, "component");
            this.properties = immutableCopy(properties, "properties");
        }

        public NodeReference ref() {
            return ref;
        }

        public Optional<String> component() {
            return component;
        }

        public List<ApplyPatch.PropertyChange> properties() {
            return properties;
        }

        @Override
        public String name() {
            return "set";
        }
    }

    public static final class AddOperation implements Operation {
        private final NodeReference parent;
        private final Optional<NodeReference> before;
        private final Optional<NodeReference> after;
        private final Optional<String> position;
        private final String component;
        private final List<ApplyPatch.PropertyChange> properties;
        private final Optional<SymbolSlot> declaredSymbol;

        AddOperation(
                NodeReference parent,
                Optional<NodeReference> before,
                Optional<NodeReference> after,
                Optional<String> position,
                String component,
                List<ApplyPatch.PropertyChange> properties,
                Optional<SymbolSlot> declaredSymbol) {
            this.parent = Objects.requireNonNull(parent, "parent");
            this.before = optional(before, "before");
            this.after = optional(after, "after");
            this.position = optional(position, "position");
            this.component = Objects.requireNonNull(component, "component");
            this.properties = immutableCopy(properties, "properties");
            this.declaredSymbol = optional(declaredSymbol, "declaredSymbol");
        }

        public NodeReference parent() {
            return parent;
        }

        public Optional<NodeReference> before() {
            return before;
        }

        public Optional<NodeReference> after() {
            return after;
        }

        public Optional<String> position() {
            return position;
        }

        public String component() {
            return component;
        }

        public List<ApplyPatch.PropertyChange> properties() {
            return properties;
        }

        public Optional<SymbolSlot> declaredSymbol() {
            return declaredSymbol;
        }

        @Override
        public String name() {
            return "add";
        }
    }

    public static final class MoveOperation implements Operation {
        private final NodeReference ref;
        private final Optional<String> component;
        private final NodeReference parent;
        private final Optional<NodeReference> before;
        private final Optional<NodeReference> after;
        private final Optional<String> position;

        MoveOperation(
                NodeReference ref,
                Optional<String> component,
                NodeReference parent,
                Optional<NodeReference> before,
                Optional<NodeReference> after,
                Optional<String> position) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.component = optional(component, "component");
            this.parent = Objects.requireNonNull(parent, "parent");
            this.before = optional(before, "before");
            this.after = optional(after, "after");
            this.position = optional(position, "position");
        }

        public NodeReference ref() {
            return ref;
        }

        public Optional<String> component() {
            return component;
        }

        public NodeReference parent() {
            return parent;
        }

        public Optional<NodeReference> before() {
            return before;
        }

        public Optional<NodeReference> after() {
            return after;
        }

        public Optional<String> position() {
            return position;
        }

        @Override
        public String name() {
            return "move";
        }
    }

    public static final class DeleteOperation implements Operation {
        private final NodeReference ref;
        private final Optional<String> component;

        DeleteOperation(NodeReference ref, Optional<String> component) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.component = optional(component, "component");
        }

        public NodeReference ref() {
            return ref;
        }

        public Optional<String> component() {
            return component;
        }

        @Override
        public String name() {
            return "delete";
        }
    }

    public static final class AppendOperation implements Operation {
        private final NodeReference ref;
        private final PropertyAddress property;
        private final Map<String, Object> row;

        AppendOperation(NodeReference ref, PropertyAddress property, Map<String, Object> row) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.property = Objects.requireNonNull(property, "property");
            this.row = immutableMap(row, "row");
        }

        public NodeReference ref() {
            return ref;
        }

        public PropertyAddress property() {
            return property;
        }

        public Map<String, Object> row() {
            return row;
        }

        @Override
        public String name() {
            return "append";
        }
    }

    public static final class InsertOperation implements Operation {
        private final NodeReference ref;
        private final PropertyAddress property;
        private final int index;
        private final Map<String, Object> row;

        InsertOperation(
                NodeReference ref, PropertyAddress property, int index, Map<String, Object> row) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.property = Objects.requireNonNull(property, "property");
            this.index = requireNonNegativeIndex(index);
            this.row = immutableMap(row, "row");
        }

        public NodeReference ref() {
            return ref;
        }

        public PropertyAddress property() {
            return property;
        }

        public int index() {
            return index;
        }

        public Map<String, Object> row() {
            return row;
        }

        @Override
        public String name() {
            return "insert";
        }
    }

    public static final class RemoveOperation implements Operation {
        private final NodeReference ref;
        private final PropertyAddress property;
        private final int index;

        RemoveOperation(NodeReference ref, PropertyAddress property, int index) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.property = Objects.requireNonNull(property, "property");
            this.index = requireNonNegativeIndex(index);
        }

        public NodeReference ref() {
            return ref;
        }

        public PropertyAddress property() {
            return property;
        }

        public int index() {
            return index;
        }

        @Override
        public String name() {
            return "remove";
        }
    }

    private static <T> List<T> immutableCopy(List<T> source, String name) {
        Objects.requireNonNull(source, name);
        List<T> copy = new ArrayList<T>(source.size());
        for (T value : source) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static <T> Optional<T> optional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source, String name) {
        Objects.requireNonNull(source, name);
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static int requireNonNegativeIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        return index;
    }
}
