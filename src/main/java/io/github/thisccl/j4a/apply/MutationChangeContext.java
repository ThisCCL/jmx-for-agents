package io.github.thisccl.j4a.apply;

import io.github.thisccl.j4a.path.PropertyAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MutationChangeContext {
    private final Map<String, Object> fields;

    private MutationChangeContext(Map<String, Object> fields) {
        this.fields = immutableMap(fields);
    }

    public static Builder partial() {
        return new Builder();
    }

    public static MutationChangeContext fromMap(Map<String, Object> fields) {
        return new MutationChangeContext(fields);
    }

    public static MutationChangeContext from(ApplyPatch.Operation operation) {
        Objects.requireNonNull(operation, "operation");
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        if (operation instanceof ApplyPatch.SetOperation) {
            ApplyPatch.SetOperation set = (ApplyPatch.SetOperation) operation;
            fields.put("ref", set.ref());
            putOptional(fields, "component", set.component());
            fields.put("properties", addresses(set.properties()));
        } else if (operation instanceof ApplyPatch.AddOperation) {
            ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) operation;
            putOptional(fields, "alias", add.as());
            fields.put("component", add.component());
            fields.put("parent", add.parent());
            putPlacement(fields, add.before(), add.after(), add.position());
            fields.put("properties", addresses(add.properties()));
        } else if (operation instanceof ApplyPatch.MoveOperation) {
            ApplyPatch.MoveOperation move = (ApplyPatch.MoveOperation) operation;
            fields.put("ref", move.ref());
            putOptional(fields, "component", move.component());
            fields.put("parent", move.parent());
            putPlacement(fields, move.before(), move.after(), move.position());
        } else if (operation instanceof ApplyPatch.DeleteOperation) {
            ApplyPatch.DeleteOperation delete = (ApplyPatch.DeleteOperation) operation;
            fields.put("ref", delete.ref());
            putOptional(fields, "component", delete.component());
        } else if (operation instanceof ApplyPatch.AppendOperation) {
            ApplyPatch.AppendOperation append = (ApplyPatch.AppendOperation) operation;
            fields.put("ref", append.ref());
            fields.put("property", address(append.property()));
        } else if (operation instanceof ApplyPatch.InsertOperation) {
            ApplyPatch.InsertOperation insert = (ApplyPatch.InsertOperation) operation;
            fields.put("ref", insert.ref());
            fields.put("property", address(insert.property()));
            fields.put("index", Integer.valueOf(insert.index()));
        } else if (operation instanceof ApplyPatch.RemoveOperation) {
            ApplyPatch.RemoveOperation remove = (ApplyPatch.RemoveOperation) operation;
            fields.put("ref", remove.ref());
            fields.put("property", address(remove.property()));
            fields.put("index", Integer.valueOf(remove.index()));
        } else {
            throw new IllegalArgumentException("Unsupported mutation operation: " + operation.name());
        }
        return new MutationChangeContext(fields);
    }

    public Map<String, Object> toMap() {
        return fields;
    }

    public Optional<String> alias() {
        Object alias = fields.get("alias");
        return alias instanceof String ? Optional.of((String) alias) : Optional.<String>empty();
    }

    private static List<List<Object>> addresses(List<ApplyPatch.PropertyChange> properties) {
        List<List<Object>> values = new ArrayList<List<Object>>(properties.size());
        for (ApplyPatch.PropertyChange property : properties) {
            values.add(address(property.property()));
        }
        return Collections.unmodifiableList(values);
    }

    private static List<Object> address(PropertyAddress property) {
        return Collections.unmodifiableList(new ArrayList<Object>(property.segments()));
    }

    private static void putPlacement(
            Map<String, Object> fields,
            Optional<String> before,
            Optional<String> after,
            Optional<String> position) {
        putOptional(fields, "before", before);
        putOptional(fields, "after", after);
        putOptional(fields, "position", position);
    }

    private static void putOptional(Map<String, Object> fields, String name, Optional<String> value) {
        if (value.isPresent()) {
            fields.put(name, value.get());
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "context field"),
                    immutableValue(Objects.requireNonNull(entry.getValue(), "context value")));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<String, Object>();

        public Builder field(String name, Object value) {
            fields.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public MutationChangeContext build() {
            return new MutationChangeContext(fields);
        }
    }
}
