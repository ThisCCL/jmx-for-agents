package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;

public final class MapPropertyCodec {
    public RecursiveValue read(MapProperty property) {
        RecursiveValue value = RuntimePropertyValueDiscovery.read(
                Objects.requireNonNull(property, "map property is required"));
        requireMap(value, "$");
        return value;
    }

    public Optional<JMeterProperty> materialize(String name, RecursiveValue value) {
        Objects.requireNonNull(name, "property name is required");
        if (name.isEmpty()) {
            throw error("$.property", "property name is required");
        }
        return materializeAt(new Target(name, null, "$"), value);
    }

    public Optional<JMeterProperty> materialize(MapProperty observed, RecursiveValue value) {
        MapProperty required = Objects.requireNonNull(observed, "observed map is required");
        return materializeAt(new Target(required.getName(), required, "$"), value);
    }

    static Optional<JMeterProperty> materializeAt(Target target, RecursiveValue value) {
        RecursiveValue required = Objects.requireNonNull(value, "recursive value is required");
        requireMap(required, target.path);
        if (target.observed == null) {
            if (!MapProperty.class.getName().equals(required.propertyClass())) {
                throw error(target.path + ".property_class",
                        "map materialization requires MapProperty");
            }
        } else if (!target.observed.getClass().getName().equals(required.propertyClass())) {
            throw error(target.path + ".property_class",
                    "property class does not match observed map class");
        }
        if (required.presence() == GraphPresence.ABSENT) {
            return Optional.empty();
        }

        List<StoredEntry> entries = validateEntries(required.entries(), target.path);
        LinkedHashMap<String, JMeterProperty> materialized =
                new LinkedHashMap<String, JMeterProperty>();
        for (int index = 0; index < entries.size(); index++) {
            StoredEntry entry = entries.get(index);
            JMeterProperty template = target.observed == null
                    ? null : target.observed.get(entry.key);
            Optional<JMeterProperty> child = Todo7RecursivePropertyCodec.materialize(
                    new Todo7RecursivePropertyCodec.Request(
                            new Todo7RecursivePropertyCodec.Binding(entry.key, template),
                            entry.value,
                            target.path + ".entries[" + index + "].value"));
            if (!child.isPresent()) {
                throw error(target.path + ".entries[" + index + "].value",
                        "map entry values must be present");
            }
            materialized.put(entry.key, child.get());
        }
        if (target.observed == null) {
            return Optional.<JMeterProperty>of(new MapProperty(target.name, materialized));
        }
        JMeterProperty cloned = target.observed.clone();
        if (cloned == target.observed
                || !cloned.getClass().equals(target.observed.getClass())
                || !(cloned instanceof MapProperty)) {
            throw error(target.path + ".property_class",
                    "observed map class cannot be cloned safely");
        }
        MapProperty result = (MapProperty) cloned;
        result.setMap(materialized);
        return Optional.of(cloned);
    }

    private static List<StoredEntry> validateEntries(
            List<RecursiveValue.MapEntry> requested, String path) {
        List<StoredEntry> entries = new ArrayList<StoredEntry>(requested.size());
        Map<String, Integer> keys = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < requested.size(); index++) {
            RecursiveValue.MapEntry entry = requested.get(index);
            String key = String.valueOf(entry.key().value());
            if (keys.put(key, Integer.valueOf(index)) != null) {
                throw new PropertyGraphRepresentationException(
                        PropertyGraphRepresentationErrorCode.DUPLICATE_MAP_KEY,
                        path + ".entries[" + index + "].key",
                        "typed key collides with storage key '" + key + "'");
            }
            entries.add(new StoredEntry(key, entry.value()));
        }
        return entries;
    }

    private static void requireMap(RecursiveValue value, String path) {
        if (value.type() != GraphType.MAP) {
            throw error(path + ".type", "map codec requires a map value");
        }
    }

    static PropertyGraphRepresentationException error(String path, String reason) {
        return new PropertyGraphRepresentationException(
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE, path, reason);
    }

    static final class Target {
        private final String name;
        private final MapProperty observed;
        private final String path;

        Target(String name, MapProperty observed, String path) {
            this.name = name;
            this.observed = observed;
            this.path = path;
        }
    }

    private static final class StoredEntry {
        private final String key;
        private final RecursiveValue value;

        private StoredEntry(String key, RecursiveValue value) {
            this.key = key;
            this.value = value;
        }
    }
}
