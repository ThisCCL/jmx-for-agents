package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphOwnership;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.GraphType;
import io.github.thisccl.j4a.jmx.property.PropertyGraphDocumentMapper;
import io.github.thisccl.j4a.jmx.property.PropertyWrite;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowDocument;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.schema.BaseTestElementSchema;
import org.apache.jmeter.testelement.schema.BooleanPropertyDescriptor;
import org.apache.jmeter.testelement.schema.DoublePropertyDescriptor;
import org.apache.jmeter.testelement.schema.FloatPropertyDescriptor;
import org.apache.jmeter.testelement.schema.IntegerPropertyDescriptor;
import org.apache.jmeter.testelement.schema.LongPropertyDescriptor;
import org.apache.jmeter.testelement.schema.PropertyDescriptor;
import org.apache.jmeter.testelement.schema.StringPropertyDescriptor;
import org.apache.jmeter.testelement.schema.TestElementPropertyDescriptor;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;

final class LocalComponentProperties {
    private static final String RUNTIME_PROVEN = "runtime-proven";
    private static final String RUNTIME_METADATA_UNAVAILABLE = "runtime-metadata-unavailable";
    private static final StableMetadataSource DEFAULT_STABLE_METADATA_SOURCE = new StableMetadataSource() {
        @Override
        public List<PropertyDescriptor<?, ?>> schemaDescriptors(TestElement defaults) {
            return LocalComponentProperties.schemaDescriptors(defaults);
        }

        @Override
        public void discoverBeanInfoProperties(
                Class<?> componentClass, Map<PropertyPath, ComponentCatalog.ComponentProperty> properties)
                throws Exception {
            LocalComponentProperties.discoverBeanInfoProperties(componentClass, properties);
        }
    };

    private LocalComponentProperties() {
    }

    interface StableMetadataSource {
        List<PropertyDescriptor<?, ?>> schemaDescriptors(TestElement defaults);

        void discoverBeanInfoProperties(
                Class<?> componentClass, Map<PropertyPath, ComponentCatalog.ComponentProperty> properties)
                throws Exception;
    }

    static StableMetadataSource syntheticStableMetadataSource(boolean unavailable) {
        return new StableMetadataSource() {
            @Override
            public List<PropertyDescriptor<?, ?>> schemaDescriptors(TestElement defaults) {
                if (unavailable) {
                    throw new IllegalStateException("Synthetic stable metadata source failure");
                }
                return Collections.emptyList();
            }

            @Override
            public void discoverBeanInfoProperties(
                    Class<?> componentClass, Map<PropertyPath, ComponentCatalog.ComponentProperty> properties) {
            }
        };
    }

    static final class MetadataProjection {
        private final List<ComponentCatalog.ComponentProperty> properties;
        private final String runtimeMetadataStatus;

        private MetadataProjection(
                List<ComponentCatalog.ComponentProperty> properties, String runtimeMetadataStatus) {
            this.properties = Collections.unmodifiableList(new ArrayList<>(properties));
            this.runtimeMetadataStatus = runtimeMetadataStatus;
        }

        List<ComponentCatalog.ComponentProperty> properties() {
            return properties;
        }

        String runtimeMetadataStatus() {
            return runtimeMetadataStatus;
        }
    }

    static List<ComponentCatalog.ComponentProperty> properties(TestElement defaults, Class<?> componentClass) {
        return properties(defaults, componentClass, LocalPropertyGraphRuntimeContext.inProcess());
    }

    static List<ComponentCatalog.ComponentProperty> properties(
            TestElement defaults,
            Class<?> componentClass,
            RuntimeContext runtimeContext) {
        return propertiesWithMetadata(defaults, componentClass, runtimeContext).properties();
    }

    static MetadataProjection propertiesWithMetadata(
            TestElement defaults,
            Class<?> componentClass,
            RuntimeContext runtimeContext) {
        return propertiesWithMetadata(defaults, componentClass, runtimeContext,
                DEFAULT_STABLE_METADATA_SOURCE,
                LocalJMeterGuiSemanticMetadata.Observation.provenEmpty());
    }

    static MetadataProjection propertiesWithMetadata(
            TestElement defaults,
            Class<?> componentClass,
            RuntimeContext runtimeContext,
            LocalJMeterGuiSemanticMetadata.Observation guiSemanticMetadata) {
        return propertiesWithMetadata(defaults, componentClass, runtimeContext,
                DEFAULT_STABLE_METADATA_SOURCE, guiSemanticMetadata);
    }

    static MetadataProjection propertiesWithMetadata(
            TestElement defaults,
            Class<?> componentClass,
            RuntimeContext runtimeContext,
            StableMetadataSource stableMetadataSource) {
        return propertiesWithMetadata(defaults, componentClass, runtimeContext,
                stableMetadataSource, LocalJMeterGuiSemanticMetadata.Observation.provenEmpty());
    }

    private static MetadataProjection propertiesWithMetadata(
            TestElement defaults,
            Class<?> componentClass,
            RuntimeContext runtimeContext,
            StableMetadataSource stableMetadataSource,
            LocalJMeterGuiSemanticMetadata.Observation guiSemanticMetadata) {
        Map<PropertyPath, ComponentCatalog.ComponentProperty> refinements = new LinkedHashMap<>();
        String runtimeMetadataStatus = RUNTIME_PROVEN;
        List<PropertyDescriptor<?, ?>> schemaDescriptors = Collections.emptyList();
        if (defaults != null) {
            try {
                List<PropertyDescriptor<?, ?>> queried = stableMetadataSource.schemaDescriptors(defaults);
                if (queried != null) {
                    schemaDescriptors = queried;
                }
            } catch (RuntimeException | LinkageError exception) {
                runtimeMetadataStatus = RUNTIME_METADATA_UNAVAILABLE;
            }
        }
        discoverSchemaProperties(schemaDescriptors, refinements);
        if (componentClass != null && TestBean.class.isAssignableFrom(componentClass)) {
            try {
                stableMetadataSource.discoverBeanInfoProperties(componentClass, refinements);
            } catch (Exception | LinkageError exception) {
                runtimeMetadataStatus = RUNTIME_METADATA_UNAVAILABLE;
            }
        }
        if (guiSemanticMetadata != null) {
            if (!guiSemanticMetadata.runtimeProven()) {
                runtimeMetadataStatus = RUNTIME_METADATA_UNAVAILABLE;
            }
            discoverGuiSemanticProperties(guiSemanticMetadata.scalarDescriptors(), refinements);
        }
        PropertyPath testElementName = propertyPath("TestElement.name");
        if (!refinements.containsKey(testElementName)) {
            refinements.put(testElementName, new ComponentCatalog.ComponentProperty(
                    testElementName, "string", true, true, null, "user", "default_property", null,
                    null, null, null, null, null, Collections.<String>emptyList(), null));
        }
        if (defaults == null) {
            return new MetadataProjection(new ArrayList<>(refinements.values()), runtimeMetadataStatus);
        }

        GraphSnapshot snapshot = new DefaultJMeterPropertyGraph().inspect(defaults, runtimeContext);
        Map<PropertyPath, ComponentCatalog.ComponentProperty> projected = new LinkedHashMap<>();
        Map<String, LocalJMeterGuiSemanticMetadata.StructuredRowConsumer> consumers =
                structuredConsumers(guiSemanticMetadata);
        for (GraphNode node : snapshot.nodes()) {
            if (node.path().segments().size() != 1) {
                continue;
            }
            String rawName = node.path().segments().get(0).name();
            projected.put(node.path(), graphProperty(
                    node, refinementFor(node.path(), refinements),
                    defaults, defaults.getPropertyOrNull(rawName), runtimeContext,
                    consumers.get(rawName)));
        }
        for (Map.Entry<PropertyPath, ComponentCatalog.ComponentProperty> refinement : refinements.entrySet()) {
            if (!projected.containsKey(refinement.getKey())) {
                projected.put(refinement.getKey(), refinement.getValue());
            }
        }
        return new MetadataProjection(new ArrayList<>(projected.values()), runtimeMetadataStatus);
    }

    private static void discoverGuiSemanticProperties(
            List<LocalJMeterGuiSemanticMetadata.ScalarDescriptor> descriptors,
            Map<PropertyPath, ComponentCatalog.ComponentProperty> properties) {
        for (LocalJMeterGuiSemanticMetadata.ScalarDescriptor descriptor : descriptors) {
            PropertyPath path = propertyPath(descriptor.property());
            if (!properties.containsKey(path)) {
                properties.put(path, new ComponentCatalog.ComponentProperty(
                        path, descriptor.type(), false, true, null, "user",
                        "gui_semantic_descriptor", null, descriptor.defaultValue(), null,
                        null, null, null, Collections.<String>emptyList(), null));
            }
        }
    }

    private static ComponentCatalog.ComponentProperty graphProperty(
            GraphNode node,
            ComponentCatalog.ComponentProperty refinement,
            TestElement owner,
            org.apache.jmeter.testelement.property.JMeterProperty observedProperty,
            RuntimeContext runtimeContext,
            LocalJMeterGuiSemanticMetadata.StructuredRowConsumer semanticConsumer) {
        java.util.Optional<RuntimeStructuredRowDocument.Projection> rows = observedProperty == null
                ? java.util.Optional.<RuntimeStructuredRowDocument.Projection>empty()
                : RuntimeStructuredRowDocument.observe(owner, observedProperty, runtimeContext);
        boolean semantic = semanticConsumer != null && (!rows.isPresent()
                || rows.get().value().get("rows") instanceof List
                && ((List<?>) rows.get().value().get("rows")).isEmpty());
        Map<String, Object> document = new PropertyGraphDocumentMapper().toDocument(
                new PropertyWrite(node.path(), node.type(), node.value()));
        Object value = rows.isPresent() || node.type().isScalar() ? null : document.get("value");
        String type = semantic || rows.isPresent() ? "rows" : publicType(node.type());
        String source = semantic ? "gui_semantic_consumer" : rows.isPresent()
                ? "runtime_observation" : node.capability().representationSource().wireName();
        String valueShape = semantic || rows.isPresent() ? "rows" : valueShape(node.type());
        String requiredValueClass = node.capability().runtimeClassConstraint()
                .valueClass().orElse(null);
        return new ComponentCatalog.ComponentProperty(
                node.path(),
                type,
                isTestElementName(node.path())
                        || node.capability().storageKeyStatus().isKey(),
                node.capability().writable(),
                node.capability().reason().orElse(null),
                node.capability().ownership().wireName(),
                source,
                value,
                refinement == null ? null : refinement.defaultValue(),
                valueShape,
                node.value().propertyClass(),
                requiredValueClass,
                semantic ? semanticConsumer.exactRowClass() : rows.isPresent() ? rows.get().rowType() : null,
                semantic ? semanticConsumer.rowProperties()
                        : rows.isPresent() ? rows.get().fields() : Collections.<String>emptyList(),
                null);
    }

    private static Map<String, LocalJMeterGuiSemanticMetadata.StructuredRowConsumer> structuredConsumers(
            LocalJMeterGuiSemanticMetadata.Observation metadata) {
        Map<String, LocalJMeterGuiSemanticMetadata.StructuredRowConsumer> consumers = new LinkedHashMap<>();
        if (metadata == null) {
            return consumers;
        }
        for (LocalJMeterGuiSemanticMetadata.StructuredRowConsumer consumer
                : metadata.structuredRowConsumers()) {
            if (consumers.put(consumer.property(), consumer) != null) {
                consumers.remove(consumer.property());
            }
        }
        return consumers;
    }

    private static ComponentCatalog.ComponentProperty refinementFor(
            PropertyPath path, Map<PropertyPath, ComponentCatalog.ComponentProperty> refinements) {
        return refinements.get(path);
    }

    private static boolean isTestElementName(PropertyPath path) {
        return path.segments().size() == 1
                && "TestElement.name".equals(path.segments().get(0).name());
    }

    private static String publicType(GraphType type) {
        return type.wireName();
    }

    private static String valueShape(GraphType type) {
        if (type == GraphType.COLLECTION) return "collection.items";
        if (type == GraphType.MAP) return "map.entries";
        if (type == GraphType.ELEMENT) return "element.properties";
        if (type == GraphType.OPAQUE) return "opaque.envelope";
        return null;
    }

    private static void discoverBeanInfoProperties(
            Class<?> componentClass, Map<PropertyPath, ComponentCatalog.ComponentProperty> properties)
            throws java.beans.IntrospectionException {
        if (componentClass == null || !TestBean.class.isAssignableFrom(componentClass)) {
            return;
        }
        discoverBeanDescriptors(componentClass, Introspector.USE_ALL_BEANINFO, properties);
        discoverBeanDescriptors(componentClass, Introspector.IGNORE_ALL_BEANINFO, properties);
    }

    private static void discoverBeanDescriptors(
            Class<?> componentClass,
            int introspectorFlags,
            Map<PropertyPath, ComponentCatalog.ComponentProperty> properties)
            throws java.beans.IntrospectionException {
        for (java.beans.PropertyDescriptor descriptor
                : Introspector.getBeanInfo(componentClass, AbstractTestElement.class, introspectorFlags)
                        .getPropertyDescriptors()) {
            if ("class".equals(descriptor.getName()) || descriptor.getWriteMethod() == null) {
                continue;
            }
            PropertyPath path = propertyPath(descriptor.getName());
            if (!properties.containsKey(path)) {
                properties.put(path, beanInfoProperty(path, descriptor.getPropertyType()));
            }
        }
    }

    private static ComponentCatalog.ComponentProperty beanInfoProperty(
            PropertyPath path, Class<?> propertyType) {
        boolean supported = isBeanPrimitive(propertyType);
        return new ComponentCatalog.ComponentProperty(
                path,
                supported ? beanType(propertyType) : "unsupported",
                false,
                supported,
                supported ? null : "JavaBeans property type is not supported by j4a patches",
                "user",
                "bean_info",
                null,
                null,
                null,
                null,
                null,
                null,
                Collections.<String>emptyList(),
                null);
    }

    private static boolean isBeanPrimitive(Class<?> propertyType) {
        return propertyType != null && (propertyType.isPrimitive()
                || Number.class.isAssignableFrom(propertyType)
                || Boolean.class.equals(propertyType)
                || Character.class.equals(propertyType)
                || CharSequence.class.isAssignableFrom(propertyType)
                || propertyType.isEnum());
    }

    private static String beanType(Class<?> propertyType) {
        if (Boolean.TYPE.equals(propertyType) || Boolean.class.equals(propertyType)) {
            return "boolean";
        }
        if (Byte.TYPE.equals(propertyType) || Short.TYPE.equals(propertyType) || Integer.TYPE.equals(propertyType)
                || Byte.class.equals(propertyType) || Short.class.equals(propertyType)
                || Integer.class.equals(propertyType)) {
            return "int";
        }
        if (Long.TYPE.equals(propertyType) || Long.class.equals(propertyType)) {
            return "long";
        }
        if (Float.TYPE.equals(propertyType) || Float.class.equals(propertyType)) {
            return "float";
        }
        if (Double.TYPE.equals(propertyType) || Double.class.equals(propertyType)) {
            return "double";
        }
        return "string";
    }

    private static List<PropertyDescriptor<?, ?>> schemaDescriptors(TestElement defaults) {
        if (defaults == null) {
            return Collections.emptyList();
        }
        BaseTestElementSchema schema = defaults.getSchema();
        if (schema == null || schema.getProperties() == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(schema.getProperties().values());
    }

    private static void discoverSchemaProperties(
            List<PropertyDescriptor<?, ?>> schemaDescriptors,
            Map<PropertyPath, ComponentCatalog.ComponentProperty> properties) {
        for (PropertyDescriptor<?, ?> descriptor : schemaDescriptors) {
            try {
                if (descriptor == null) {
                    continue;
                }
                String name = descriptor.getName();
                if (name == null) {
                    continue;
                }
                if (TestElement.GUI_CLASS.equals(name) || TestElement.TEST_CLASS.equals(name)) {
                    continue;
                }
                PropertyPath path = propertyPath(name);
                properties.put(path, schemaProperty(path, descriptor));
            } catch (RuntimeException | LinkageError exception) {
                continue;
            }
        }
    }

    private static ComponentCatalog.ComponentProperty schemaProperty(
            PropertyPath path, PropertyDescriptor<?, ?> descriptor) {
        boolean supported = isPrimitiveDescriptor(descriptor);
        return new ComponentCatalog.ComponentProperty(
                path,
                supported ? descriptorType(descriptor) : "unsupported",
                propertyPath("TestElement.name").equals(path),
                supported,
                supported ? null : "structured JMeter schema property requires a runtime-observed writable representation",
                "user",
                "schema_descriptor",
                null,
                supported ? descriptor.getDefaultValue() : null,
                null,
                null,
                null,
                null,
                Collections.<String>emptyList(),
                null);
    }

    private static boolean isPrimitiveDescriptor(PropertyDescriptor<?, ?> descriptor) {
        return descriptor instanceof StringPropertyDescriptor
                || descriptor instanceof BooleanPropertyDescriptor
                || descriptor instanceof IntegerPropertyDescriptor
                || descriptor instanceof LongPropertyDescriptor
                || descriptor instanceof FloatPropertyDescriptor
                || descriptor instanceof DoublePropertyDescriptor;
    }

    private static String descriptorType(PropertyDescriptor<?, ?> descriptor) {
        if (descriptor instanceof BooleanPropertyDescriptor) {
            return "boolean";
        }
        if (descriptor instanceof IntegerPropertyDescriptor) {
            return "int";
        }
        if (descriptor instanceof LongPropertyDescriptor) {
            return "long";
        }
        if (descriptor instanceof FloatPropertyDescriptor) {
            return "float";
        }
        if (descriptor instanceof DoublePropertyDescriptor) {
            return "double";
        }
        return "string";
    }

    private static PropertyPath propertyPath(String property) {
        return new PropertyPath(Collections.singletonList(PropertyPathSegment.property(property)));
    }

    private static String propertyType(JMeterProperty property) {
        if (property instanceof BooleanProperty) {
            return "boolean";
        }
        if (property instanceof IntegerProperty) {
            return "int";
        }
        if (property instanceof LongProperty) {
            return "long";
        }
        if (property instanceof FloatProperty) {
            return "float";
        }
        if (property instanceof DoubleProperty) {
            return "double";
        }
        if (property instanceof StringProperty || property.getObjectValue() instanceof String) {
            return "string";
        }
        return "string";
    }
}
