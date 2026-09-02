package io.github.thisccl.j4a.components;

import io.github.thisccl.j4a.path.PropertyAddressDocument;
import io.github.thisccl.j4a.path.PropertyPath;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public final class ComponentCatalog {
    private ComponentCatalog() {
    }

    public static final class ComponentCategory {
        private final String category;
        private final String label;
        private final int componentCount;

        public ComponentCategory(String category, String label, int componentCount) {
            this.category = category;
            this.label = label;
            this.componentCount = componentCount;
        }

        public String category() {
            return category;
        }

        public String label() {
            return label;
        }

        public int componentCount() {
            return componentCount;
        }
    }

    public static final class ComponentDefinition {
        private final String category;
        private final String categoryLabel;
        private final String component;
        private final String label;
        private final List<ComponentProperty> properties;
        private final String menuClassName;
        private final String registrationKind;
        private final String runtimeMetadataStatus;

        public ComponentDefinition(String category, String categoryLabel, String component, String label,
                List<ComponentProperty> properties, String menuClassName, String registrationKind) {
            this(category, categoryLabel, component, label, properties, menuClassName, registrationKind, null);
        }

        public ComponentDefinition(String category, String categoryLabel, String component, String label,
                List<ComponentProperty> properties, String menuClassName, String registrationKind,
                String runtimeMetadataStatus) {
            this.category = category;
            this.categoryLabel = categoryLabel;
            this.component = component;
            this.label = label;
            this.properties = Collections.unmodifiableList(new ArrayList<>(properties));
            this.menuClassName = menuClassName;
            this.registrationKind = registrationKind;
            this.runtimeMetadataStatus = runtimeMetadataStatus;
        }

        public String category() {
            return category;
        }

        public String categoryLabel() {
            return categoryLabel;
        }

        public String component() {
            return component;
        }

        public String label() {
            return label;
        }

        public List<ComponentProperty> properties() {
            return properties;
        }

        public String menuClassName() {
            return menuClassName;
        }

        public String registrationKind() { return registrationKind; }

        public String runtimeMetadataStatus() { return runtimeMetadataStatus; }

    }

    public static final class ComponentProperty {
        private final PropertyPath property;
        private final String type;
        private final boolean key;
        private final boolean writable;
        private final String reason;
        private final String ownership;
        private final String representationSource;
        private final Object value;
        private final Object defaultValue;
        private final String valueShape;
        private final String requiredPropertyClass;
        private final String requiredValueClass;
        private final String rowType;
        private final List<String> rowProperties;
        private final Object valueTemplate;

        public ComponentProperty(
                PropertyPath property,
                String type,
                boolean key,
                boolean writable,
                String reason,
                String ownership,
                String representationSource,
                Object value,
                Object defaultValue,
                String valueShape,
                String requiredPropertyClass,
                String requiredValueClass,
                String rowType,
                List<String> rowProperties,
                Object valueTemplate) {
            this.property = property;
            this.type = type;
            this.key = key;
            this.writable = writable;
            this.reason = reason;
            this.ownership = ownership;
            this.representationSource = representationSource;
            this.value = value;
            this.defaultValue = defaultValue;
            this.valueShape = valueShape;
            this.requiredPropertyClass = requiredPropertyClass;
            this.requiredValueClass = requiredValueClass;
            this.rowType = rowType;
            this.rowProperties = Collections.unmodifiableList(new ArrayList<>(rowProperties));
            this.valueTemplate = valueTemplate;
        }

        public List<Object> address() {
            return PropertyAddressDocument.scalarSegments(property);
        }

        public String type() {
            return type;
        }

        public boolean key() {
            return key;
        }

        public boolean writable() {
            return writable;
        }

        public String reason() {
            return reason;
        }

        public String source() {
            return representationSource;
        }

        public String ownership() {
            return ownership;
        }

        public String representationSource() {
            return representationSource;
        }

        public Object value() {
            return value;
        }

        public Object defaultValue() {
            return defaultValue;
        }

        public String valueShape() {
            return valueShape;
        }

        public String requiredPropertyClass() {
            return requiredPropertyClass;
        }

        public String requiredValueClass() {
            return requiredValueClass;
        }

        public String rowType() {
            return rowType;
        }

        public List<String> rowProperties() {
            return rowProperties;
        }

        public Object valueTemplate() {
            return valueTemplate;
        }
    }
}
