package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RecursiveValue {
    private final GraphType type;
    private final GraphPresence presence;
    private final String propertyClass;
    private final Object scalarValue;
    private final List<RecursiveValue> items;
    private final List<MapEntry> entries;
    private final String elementClass;
    private final List<PropertyWrite> properties;
    private final OpaqueValue opaqueValue;

    private RecursiveValue(
            GraphType type,
            GraphPresence presence,
            String propertyClass,
            Object scalarValue,
            List<RecursiveValue> items,
            List<MapEntry> entries,
            String elementClass,
            List<PropertyWrite> properties,
            OpaqueValue opaqueValue) {
        this.type = Objects.requireNonNull(type, "graph type is required");
        this.presence = Objects.requireNonNull(presence, "presence is required");
        this.propertyClass = requireText(propertyClass, "property class");
        this.scalarValue = scalarValue;
        this.items = immutableCopy(items, "collection items");
        this.entries = immutableCopy(entries, "map entries");
        this.elementClass = elementClass;
        this.properties = immutableCopy(properties, "element properties");
        this.opaqueValue = opaqueValue;
    }

    public static RecursiveValue absent(GraphType type, String propertyClass) {
        return new RecursiveValue(
                type,
                GraphPresence.ABSENT,
                propertyClass,
                null,
                Collections.<RecursiveValue>emptyList(),
                Collections.<MapEntry>emptyList(),
                null,
                Collections.<PropertyWrite>emptyList(),
                null);
    }

    public static RecursiveValue scalar(GraphType type, String propertyClass, Object value) {
        if (type == GraphType.NULL || !type.isScalar()) {
            throw new IllegalArgumentException("scalar factory requires a non-null scalar type");
        }
        Object scalar = TypedScalarMapKey.requireScalarValue(type, value);
        return new RecursiveValue(
                type,
                GraphPresence.PRESENT,
                propertyClass,
                scalar,
                Collections.<RecursiveValue>emptyList(),
                Collections.<MapEntry>emptyList(),
                null,
                Collections.<PropertyWrite>emptyList(),
                null);
    }

    public static RecursiveValue presentNull(String propertyClass) {
        return new RecursiveValue(
                GraphType.NULL,
                GraphPresence.PRESENT,
                propertyClass,
                null,
                Collections.<RecursiveValue>emptyList(),
                Collections.<MapEntry>emptyList(),
                null,
                Collections.<PropertyWrite>emptyList(),
                null);
    }

    public static RecursiveValue collection(String propertyClass, List<RecursiveValue> items) {
        return new RecursiveValue(
                GraphType.COLLECTION,
                GraphPresence.PRESENT,
                propertyClass,
                null,
                items,
                Collections.<MapEntry>emptyList(),
                null,
                Collections.<PropertyWrite>emptyList(),
                null);
    }

    public static RecursiveValue map(String propertyClass, List<MapEntry> entries) {
        Objects.requireNonNull(entries, "map entries are required");
        Set<TypedScalarMapKey> keys = new HashSet<TypedScalarMapKey>();
        for (MapEntry entry : entries) {
            Objects.requireNonNull(entry, "map entry is required");
            if (!keys.add(entry.key())) {
                throw new IllegalArgumentException("map entries require unique typed keys");
            }
        }
        return new RecursiveValue(
                GraphType.MAP,
                GraphPresence.PRESENT,
                propertyClass,
                null,
                Collections.<RecursiveValue>emptyList(),
                entries,
                null,
                Collections.<PropertyWrite>emptyList(),
                null);
    }

    public static RecursiveValue element(
            String propertyClass, String elementClass, List<PropertyWrite> properties) {
        return new RecursiveValue(
                GraphType.ELEMENT,
                GraphPresence.PRESENT,
                propertyClass,
                null,
                Collections.<RecursiveValue>emptyList(),
                Collections.<MapEntry>emptyList(),
                requireText(elementClass, "element class"),
                properties,
                null);
    }

    public static RecursiveValue opaque(String propertyClass, OpaqueValue opaqueValue) {
        Objects.requireNonNull(opaqueValue, "opaque value is required");
        if (!propertyClass.equals(opaqueValue.outerPropertyClass())) {
            throw new IllegalArgumentException("opaque outer property class must match property class");
        }
        return new RecursiveValue(
                GraphType.OPAQUE,
                GraphPresence.PRESENT,
                propertyClass,
                null,
                Collections.<RecursiveValue>emptyList(),
                Collections.<MapEntry>emptyList(),
                null,
                Collections.<PropertyWrite>emptyList(),
                opaqueValue);
    }

    public GraphType type() {
        return type;
    }

    public GraphPresence presence() {
        return presence;
    }

    public String propertyClass() {
        return propertyClass;
    }

    public Object scalarValue() {
        requirePayload(type.isScalar(), "scalar");
        return scalarValue;
    }

    public List<RecursiveValue> items() {
        requirePayload(type == GraphType.COLLECTION, "collection");
        return items;
    }

    public List<MapEntry> entries() {
        requirePayload(type == GraphType.MAP, "map");
        return entries;
    }

    public String elementClass() {
        requirePayload(type == GraphType.ELEMENT, "element");
        return elementClass;
    }

    public List<PropertyWrite> properties() {
        requirePayload(type == GraphType.ELEMENT, "element");
        return properties;
    }

    public OpaqueValue opaqueValue() {
        requirePayload(type == GraphType.OPAQUE, "opaque");
        return opaqueValue;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RecursiveValue)) {
            return false;
        }
        RecursiveValue that = (RecursiveValue) other;
        return type == that.type
                && presence == that.presence
                && propertyClass.equals(that.propertyClass)
                && Objects.equals(scalarValue, that.scalarValue)
                && items.equals(that.items)
                && entries.equals(that.entries)
                && Objects.equals(elementClass, that.elementClass)
                && properties.equals(that.properties)
                && Objects.equals(opaqueValue, that.opaqueValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                type,
                presence,
                propertyClass,
                scalarValue,
                items,
                entries,
                elementClass,
                properties,
                opaqueValue);
    }

    @Override
    public String toString() {
        return "RecursiveValue{type=" + type.wireName()
                + ", presence=" + presence
                + ", propertyClass=" + propertyClass
                + ", scalarValue=" + scalarValue
                + ", items=" + items
                + ", entries=" + entries
                + ", elementClass=" + elementClass
                + ", properties=" + properties
                + ", opaqueValue=" + opaqueValue
                + "}";
    }

    private void requirePayload(boolean expectedFamily, String family) {
        if (presence != GraphPresence.PRESENT || !expectedFamily) {
            throw new IllegalStateException(family + " payload is not available");
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static <T> List<T> immutableCopy(List<T> values, String label) {
        Objects.requireNonNull(values, label + " are required");
        ArrayList<T> copy = new ArrayList<T>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, label + " must not contain null"));
        }
        return Collections.unmodifiableList(copy);
    }

    public static final class MapEntry {
        private final TypedScalarMapKey key;
        private final RecursiveValue value;

        public MapEntry(TypedScalarMapKey key, RecursiveValue value) {
            this.key = Objects.requireNonNull(key, "map entry key is required");
            this.value = Objects.requireNonNull(value, "map entry value is required");
        }

        public TypedScalarMapKey key() {
            return key;
        }

        public RecursiveValue value() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MapEntry)) {
                return false;
            }
            MapEntry that = (MapEntry) other;
            return key.equals(that.key) && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
    }

    public static final class OpaqueValue {
        public static final String FORMAT = "jmeter-save-element-xml-v1";

        private final String format;
        private final String baseDigest;
        private final String outerPropertyClass;
        private final String runtimeFingerprint;
        private final String payload;

        public OpaqueValue(
                String format,
                String baseDigest,
                String outerPropertyClass,
                String runtimeFingerprint,
                String payload) {
            this.format = requireText(format, "opaque format");
            if (!FORMAT.equals(format)) {
                throw new IllegalArgumentException("unrecognized opaque format: " + format);
            }
            this.baseDigest = requireText(baseDigest, "opaque base digest");
            this.outerPropertyClass = requireText(outerPropertyClass, "opaque outer property class");
            this.runtimeFingerprint = requireText(runtimeFingerprint, "opaque runtime fingerprint");
            this.payload = Objects.requireNonNull(payload, "opaque payload is required");
        }

        public String format() {
            return format;
        }

        public String baseDigest() {
            return baseDigest;
        }

        public String outerPropertyClass() {
            return outerPropertyClass;
        }

        public String runtimeFingerprint() {
            return runtimeFingerprint;
        }

        public String payload() {
            return payload;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OpaqueValue)) {
                return false;
            }
            OpaqueValue that = (OpaqueValue) other;
            return format.equals(that.format)
                    && baseDigest.equals(that.baseDigest)
                    && outerPropertyClass.equals(that.outerPropertyClass)
                    && runtimeFingerprint.equals(that.runtimeFingerprint)
                    && payload.equals(that.payload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(format, baseDigest, outerPropertyClass, runtimeFingerprint, payload);
        }
    }
}
