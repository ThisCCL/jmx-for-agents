package io.github.thisccl.j4a.apply;

import io.github.thisccl.j4a.path.PropertyAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ApplyPatch {
    private final List<Change> changes;

    public ApplyPatch(List<Change> changes) {
        this.changes = copyList(changes);
    }

    public List<Change> changes() {
        return changes;
    }

    public static final class Change {
        private final Operation operation;

        public Change(Operation operation) {
            this.operation = operation;
        }

        public Operation operation() {
            return operation;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Change)) {
                return false;
            }
            Change change = (Change) other;
            return Objects.equals(operation, change.operation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operation);
        }

        @Override
        public String toString() {
            return "Change[operation=" + operation + "]";
        }
    }

    public interface Operation {
        String name();
    }

    public interface ReferenceExpression {
        String spelling();
    }

    public static final class OrdinaryReference implements ReferenceExpression {
        private final String spelling;

        public OrdinaryReference(String spelling) {
            this.spelling = requireReferenceSpelling(spelling);
            if (this.spelling.startsWith("$")) {
                throw new ApplyPatchParseException(
                        "ordinary reference must not start with '$'; use an alias reference instead");
            }
        }

        @Override
        public String spelling() {
            return spelling;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OrdinaryReference
                    && spelling.equals(((OrdinaryReference) other).spelling);
        }

        @Override
        public int hashCode() {
            return Objects.hash(spelling);
        }

        @Override
        public String toString() {
            return "OrdinaryReference[spelling=" + spelling + "]";
        }
    }

    public static final class AliasReference implements ReferenceExpression {
        private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
        private final String alias;

        public AliasReference(String alias) {
            this.alias = requireAliasName(alias);
        }

        public String alias() {
            return alias;
        }

        @Override
        public String spelling() {
            return "$" + alias;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AliasReference
                    && alias.equals(((AliasReference) other).alias);
        }

        @Override
        public int hashCode() {
            return Objects.hash(alias);
        }

        @Override
        public String toString() {
            return "AliasReference[alias=" + alias + "]";
        }
    }

    private static ReferenceExpression referenceExpression(String spelling) {
        String value = requireReferenceSpelling(spelling);
        return value.startsWith("$")
                ? new AliasReference(value.substring(1))
                : new OrdinaryReference(value);
    }

    private static String requireReferenceSpelling(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new ApplyPatchParseException("reference must be a non-empty string");
        }
        return value;
    }

    private static String requireAliasName(String value) {
        if (value == null || !AliasReference.NAME_PATTERN.matcher(value).matches()) {
            throw new ApplyPatchParseException(
                    "invalid alias name '" + value + "'; expected [A-Za-z][A-Za-z0-9_-]{0,63}");
        }
        return value;
    }

    public static final class SetOperation implements Operation {
        private final ReferenceExpression refExpression;
        private final Optional<String> component;
        private final List<PropertyChange> properties;

        public SetOperation(String ref, Optional<String> component, List<PropertyChange> properties) {
            this(referenceExpression(ref), component, properties);
        }

        public SetOperation(ReferenceExpression ref, Optional<String> component, List<PropertyChange> properties) {
            this.refExpression = Objects.requireNonNull(ref);
            this.component = component == null ? Optional.empty() : component;
            this.properties = copyList(properties);
        }

        public String ref() {
            return refExpression.spelling();
        }

        public ReferenceExpression refExpression() {
            return refExpression;
        }

        public Optional<String> component() {
            return component;
        }

        public List<PropertyChange> properties() {
            return properties;
        }

        @Override
        public String name() {
            return "set";
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SetOperation)) {
                return false;
            }
            SetOperation operation = (SetOperation) other;
            return Objects.equals(refExpression, operation.refExpression)
                    && Objects.equals(component, operation.component)
                    && Objects.equals(properties, operation.properties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(refExpression, component, properties);
        }

        @Override
        public String toString() {
            return "SetOperation[ref=" + ref()
                    + ", component=" + component
                    + ", properties=" + properties + "]";
        }
    }

    public static final class AddOperation implements Operation {
        private final ReferenceExpression parentExpression;
        private final Optional<ReferenceExpression> beforeExpressions;
        private final Optional<ReferenceExpression> afterExpressions;
        private final Optional<String> position;
        private final String component;
        private final List<PropertyChange> properties;
        private final Optional<String> alias;

        public AddOperation(
                String parent,
                Optional<String> before,
                Optional<String> after,
                Optional<String> position,
                String component,
                List<PropertyChange> properties) {
            this(referenceExpression(parent),
                    referenceOptional(before), referenceOptional(after), position, component, properties,
                    Optional.empty());
        }

        public AddOperation(
                String parent,
                Optional<String> before,
                Optional<String> after,
                Optional<String> position,
                String component,
                List<PropertyChange> properties,
                Optional<String> alias) {
            this(referenceExpression(parent),
                    referenceOptional(before), referenceOptional(after), position, component, properties, alias);
        }

        public AddOperation(
                ReferenceExpression parent,
                Optional<ReferenceExpression> before,
                Optional<ReferenceExpression> after,
                Optional<String> position,
                String component,
                List<PropertyChange> properties,
                Optional<String> alias) {
            this.parentExpression = Objects.requireNonNull(parent);
            this.beforeExpressions = before == null ? Optional.empty() : before;
            this.afterExpressions = after == null ? Optional.empty() : after;
            this.position = position == null ? Optional.empty() : position;
            this.component = component;
            this.properties = copyList(properties);
            this.alias = alias == null ? Optional.empty() : alias.map(ApplyPatch::requireAliasName);
        }

        public AddOperation(
                ReferenceExpression parent,
                Optional<ReferenceExpression> before,
                Optional<ReferenceExpression> after,
                Optional<String> position,
                String component,
                List<PropertyChange> properties) {
            this(parent, before, after, position, component, properties, Optional.empty());
        }

        public String parent() {
            return parentExpression.spelling();
        }

        public ReferenceExpression parentExpression() {
            return parentExpression;
        }

        public Optional<String> before() {
            return beforeExpressions.map(ReferenceExpression::spelling);
        }

        public Optional<ReferenceExpression> beforeExpression() {
            return beforeExpressions;
        }

        public Optional<String> after() {
            return afterExpressions.map(ReferenceExpression::spelling);
        }

        public Optional<ReferenceExpression> afterExpression() {
            return afterExpressions;
        }

        public Optional<String> position() {
            return position;
        }

        public String component() {
            return component;
        }

        public List<PropertyChange> properties() {
            return properties;
        }

        public Optional<String> as() {
            return alias;
        }

        @Override
        public String name() {
            return "add";
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof AddOperation)) {
                return false;
            }
            AddOperation operation = (AddOperation) other;
            return Objects.equals(parentExpression, operation.parentExpression)
                    && Objects.equals(beforeExpressions, operation.beforeExpressions)
                    && Objects.equals(afterExpressions, operation.afterExpressions)
                    && Objects.equals(position, operation.position)
                    && Objects.equals(component, operation.component)
                    && Objects.equals(properties, operation.properties)
                    && Objects.equals(alias, operation.alias);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentExpression, beforeExpressions, afterExpressions, position, component,
                    properties, alias);
        }

        @Override
        public String toString() {
            return "AddOperation[parent=" + parent()
                    + ", before=" + before()
                    + ", after=" + after()
                    + ", position=" + position
                    + ", component=" + component
                    + ", properties=" + properties
                    + ", as=" + alias + "]";
        }
    }

    public static final class MoveOperation implements Operation {
        private final ReferenceExpression refExpression;
        private final Optional<String> component;
        private final ReferenceExpression parentExpression;
        private final Optional<ReferenceExpression> beforeExpressions;
        private final Optional<ReferenceExpression> afterExpressions;
        private final Optional<String> position;

        public MoveOperation(
                String ref,
                Optional<String> component,
                String parent,
                Optional<String> before,
                Optional<String> after,
                Optional<String> position) {
            this(referenceExpression(ref), component, referenceExpression(parent),
                    referenceOptional(before), referenceOptional(after), position);
        }

        public MoveOperation(
                ReferenceExpression ref,
                Optional<String> component,
                ReferenceExpression parent,
                Optional<ReferenceExpression> before,
                Optional<ReferenceExpression> after,
                Optional<String> position) {
            this.refExpression = Objects.requireNonNull(ref);
            this.component = component == null ? Optional.empty() : component;
            this.parentExpression = Objects.requireNonNull(parent);
            this.beforeExpressions = before == null ? Optional.empty() : before;
            this.afterExpressions = after == null ? Optional.empty() : after;
            this.position = position == null ? Optional.empty() : position;
        }

        public String ref() {
            return refExpression.spelling();
        }

        public ReferenceExpression refExpression() {
            return refExpression;
        }

        public Optional<String> component() {
            return component;
        }

        public String parent() {
            return parentExpression.spelling();
        }

        public ReferenceExpression parentExpression() {
            return parentExpression;
        }

        public Optional<String> before() {
            return beforeExpressions.map(ReferenceExpression::spelling);
        }

        public Optional<ReferenceExpression> beforeExpression() {
            return beforeExpressions;
        }

        public Optional<String> after() {
            return afterExpressions.map(ReferenceExpression::spelling);
        }

        public Optional<ReferenceExpression> afterExpression() {
            return afterExpressions;
        }

        public Optional<String> position() {
            return position;
        }

        @Override
        public String name() {
            return "move";
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MoveOperation)) {
                return false;
            }
            MoveOperation operation = (MoveOperation) other;
            return Objects.equals(refExpression, operation.refExpression)
                    && Objects.equals(component, operation.component)
                    && Objects.equals(parentExpression, operation.parentExpression)
                    && Objects.equals(beforeExpressions, operation.beforeExpressions)
                    && Objects.equals(afterExpressions, operation.afterExpressions)
                    && Objects.equals(position, operation.position);
        }

        @Override
        public int hashCode() {
            return Objects.hash(refExpression, component, parentExpression, beforeExpressions,
                    afterExpressions, position);
        }

        @Override
        public String toString() {
            return "MoveOperation[ref=" + ref()
                    + ", component=" + component
                    + ", parent=" + parent()
                    + ", before=" + before()
                    + ", after=" + after()
                    + ", position=" + position + "]";
        }
    }

    public static final class DeleteOperation implements Operation {
        private final ReferenceExpression refExpression;
        private final Optional<String> component;

        public DeleteOperation(String ref, Optional<String> component) {
            this(referenceExpression(ref), component);
        }

        public DeleteOperation(ReferenceExpression ref, Optional<String> component) {
            this.refExpression = Objects.requireNonNull(ref);
            this.component = component == null ? Optional.empty() : component;
        }

        public String ref() {
            return refExpression.spelling();
        }

        public ReferenceExpression refExpression() {
            return refExpression;
        }

        public Optional<String> component() {
            return component;
        }

        @Override
        public String name() {
            return "delete";
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DeleteOperation)) {
                return false;
            }
            DeleteOperation operation = (DeleteOperation) other;
            return Objects.equals(refExpression, operation.refExpression)
                    && Objects.equals(component, operation.component);
        }

        @Override
        public int hashCode() {
            return Objects.hash(refExpression, component);
        }

        @Override
        public String toString() {
            return "DeleteOperation[ref=" + ref() + ", component=" + component + "]";
        }
    }

    public static final class AppendOperation implements Operation {
        private final ReferenceExpression refExpression;
        private final PropertyAddress property;
        private final Map<String, Object> row;

        public AppendOperation(String ref, PropertyAddress property, Map<String, Object> row) {
            this(referenceExpression(ref), property, row);
        }

        public AppendOperation(
                ReferenceExpression ref, PropertyAddress property, Map<String, Object> row) {
            this.refExpression = Objects.requireNonNull(ref, "ref");
            this.property = Objects.requireNonNull(property, "property");
            this.row = copyMap(row, "row");
        }

        public String ref() {
            return refExpression.spelling();
        }

        public ReferenceExpression refExpression() {
            return refExpression;
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
        private final ReferenceExpression refExpression;
        private final PropertyAddress property;
        private final int index;
        private final Map<String, Object> row;

        public InsertOperation(
                String ref, PropertyAddress property, int index, Map<String, Object> row) {
            this(referenceExpression(ref), property, index, row);
        }

        public InsertOperation(
                ReferenceExpression ref,
                PropertyAddress property,
                int index,
                Map<String, Object> row) {
            this.refExpression = Objects.requireNonNull(ref, "ref");
            this.property = Objects.requireNonNull(property, "property");
            this.index = requireNonNegativeIndex(index);
            this.row = copyMap(row, "row");
        }

        public String ref() {
            return refExpression.spelling();
        }

        public ReferenceExpression refExpression() {
            return refExpression;
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
        private final ReferenceExpression refExpression;
        private final PropertyAddress property;
        private final int index;

        public RemoveOperation(String ref, PropertyAddress property, int index) {
            this(referenceExpression(ref), property, index);
        }

        public RemoveOperation(ReferenceExpression ref, PropertyAddress property, int index) {
            this.refExpression = Objects.requireNonNull(ref, "ref");
            this.property = Objects.requireNonNull(property, "property");
            this.index = requireNonNegativeIndex(index);
        }

        public String ref() {
            return refExpression.spelling();
        }

        public ReferenceExpression refExpression() {
            return refExpression;
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

    public static final class PropertyChange {
        private final PropertyAddress property;
        private final Object value;
        private final ValueType type;

        public PropertyChange(PropertyAddress property, Object value, ValueType type) {
            this.property = Objects.requireNonNull(property, "property");
            this.value = value;
            this.type = type;
        }

        public PropertyAddress property() {
            return property;
        }

        public Object value() {
            return value;
        }

        public ValueType type() {
            return type;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PropertyChange)) {
                return false;
            }
            PropertyChange change = (PropertyChange) other;
            return Objects.equals(property, change.property)
                    && Objects.equals(value, change.value)
                    && type == change.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(property, value, type);
        }

        @Override
        public String toString() {
            return "PropertyChange[property=" + property
                    + ", value=" + value
                    + ", type=" + type + "]";
        }
    }

    public enum ValueType {
        STRING,
        BOOLEAN,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        NULL,
        ROWS,
        COLLECTION,
        MAP,
        ELEMENT,
        OPAQUE,
        RAW;

        public static ValueType parse(String value) {
            if (value == null) {
                throw new ApplyPatchParseException("unsupported value type 'null'");
            }
            for (ValueType type : values()) {
                if (type.name().equalsIgnoreCase(value)) {
                    return type;
                }
            }
            if (isRemovedNamedRowType(value)) {
                throw new ApplyPatchParseException(
                        "legacy structured value type '" + value
                                + "' is no longer accepted; use type 'rows' with the "
                                + "row_type and fields emitted by focused read or components details");
            }
            throw new ApplyPatchParseException("unsupported value type '" + value + "'");
        }

        public boolean structuredCollection() {
            return this == ROWS;
        }

        private static boolean isRemovedNamedRowType(String value) {
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            return "arguments".equals(normalized)
                    || "headers".equals(normalized)
                    || "http_files".equals(normalized)
                    || "authorizations".equals(normalized)
                    || "cookies".equals(normalized)
                    || "dns_servers".equals(normalized)
                    || "dns_hosts".equals(normalized);
        }

        public boolean recursiveGraph() {
            return this == COLLECTION || this == MAP || this == ELEMENT || this == OPAQUE;
        }

        public boolean graphScalar() {
            return this == STRING || this == BOOLEAN || this == INT || this == LONG
                    || this == FLOAT || this == DOUBLE || this == NULL;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ApplyPatch)) {
            return false;
        }
        ApplyPatch patch = (ApplyPatch) other;
        return Objects.equals(changes, patch.changes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(changes);
    }

    @Override
    public String toString() {
        return "ApplyPatch[changes=" + changes + "]";
    }

    private static <T> List<T> copyList(List<T> source) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(source));
        for (T value : copy) {
            Objects.requireNonNull(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source, String name) {
        Objects.requireNonNull(source, name);
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static int requireNonNegativeIndex(int index) {
        if (index < 0) {
            throw new ApplyPatchParseException("index must be zero or greater");
        }
        return index;
    }

    private static Optional<ReferenceExpression> referenceOptional(Optional<String> value) {
        if (value == null || !value.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(referenceExpression(value.get()));
    }
}
