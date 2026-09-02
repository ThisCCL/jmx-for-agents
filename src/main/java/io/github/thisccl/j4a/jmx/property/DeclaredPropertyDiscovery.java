package io.github.thisccl.j4a.jmx.property;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.schema.BaseTestElementSchema;
import org.apache.jmeter.testelement.schema.BooleanPropertyDescriptor;
import org.apache.jmeter.testelement.schema.ClassPropertyDescriptor;
import org.apache.jmeter.testelement.schema.CollectionPropertyDescriptor;
import org.apache.jmeter.testelement.schema.DoublePropertyDescriptor;
import org.apache.jmeter.testelement.schema.FloatPropertyDescriptor;
import org.apache.jmeter.testelement.schema.IntegerPropertyDescriptor;
import org.apache.jmeter.testelement.schema.LongPropertyDescriptor;
import org.apache.jmeter.testelement.schema.PropertyDescriptor;
import org.apache.jmeter.testelement.schema.StringPropertyDescriptor;
import org.apache.jmeter.testelement.schema.TestElementPropertyDescriptor;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class DeclaredPropertyDiscovery {
    private DeclaredPropertyDiscovery() {
    }

    static List<Declaration> declarations(TestElement element) {
        List<Declaration> declarations = new ArrayList<Declaration>();
        addSchemaDeclarations(element, declarations);
        addBeanInfoDeclarations(element, declarations);
        return Collections.unmodifiableList(declarations);
    }

    private static void addSchemaDeclarations(
            TestElement element, List<Declaration> declarations) {
        BaseTestElementSchema schema = element.getSchema();
        if (schema == null || schema.getProperties() == null) {
            return;
        }
        for (Object value : schema.getProperties().values()) {
            if (!(value instanceof PropertyDescriptor<?, ?>)) {
                continue;
            }
            try {
                PropertyDescriptor<?, ?> descriptor = (PropertyDescriptor<?, ?>) value;
                String name = descriptor.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                declarations.add(new Declaration(
                        name, schemaShape(descriptor), RepresentationSource.JMETER_SCHEMA));
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private static void addBeanInfoDeclarations(
            TestElement element, List<Declaration> declarations) {
        if (!(element instanceof TestBean)) {
            return;
        }
        try {
            for (java.beans.PropertyDescriptor descriptor : Introspector.getBeanInfo(
                    element.getClass(), AbstractTestElement.class, Introspector.USE_ALL_BEANINFO)
                    .getPropertyDescriptors()) {
                String name = descriptor.getName();
                if (name == null || name.isEmpty() || descriptor.getWriteMethod() == null) {
                    continue;
                }
                declarations.add(new Declaration(
                        name, beanShape(descriptor.getPropertyType()), RepresentationSource.TEST_BEAN));
            }
        } catch (IntrospectionException exception) {
            return;
        }
    }

    private static Shape schemaShape(PropertyDescriptor<?, ?> descriptor) {
        if (descriptor instanceof BooleanPropertyDescriptor<?>) {
            return shape(GraphType.BOOLEAN, BooleanProperty.class);
        }
        if (descriptor instanceof IntegerPropertyDescriptor<?>) {
            return shape(GraphType.INT, IntegerProperty.class);
        }
        if (descriptor instanceof LongPropertyDescriptor<?>) {
            return shape(GraphType.LONG, LongProperty.class);
        }
        if (descriptor instanceof FloatPropertyDescriptor<?>) {
            return shape(GraphType.FLOAT, FloatProperty.class);
        }
        if (descriptor instanceof DoublePropertyDescriptor<?>) {
            return shape(GraphType.DOUBLE, DoubleProperty.class);
        }
        if (descriptor instanceof StringPropertyDescriptor<?>
                || descriptor instanceof ClassPropertyDescriptor<?, ?>) {
            return shape(GraphType.STRING, StringProperty.class);
        }
        if (descriptor instanceof CollectionPropertyDescriptor<?>) {
            return shape(GraphType.COLLECTION, CollectionProperty.class);
        }
        if (descriptor instanceof TestElementPropertyDescriptor<?, ?>) {
            Class<?> valueClass = ((TestElementPropertyDescriptor<?, ?>) descriptor).getKlass();
            return new Shape(GraphType.ELEMENT, TestElementProperty.class.getName(),
                    valueClass == null ? null : valueClass.getName());
        }
        return null;
    }

    private static Shape beanShape(Class<?> valueClass) {
        if (valueClass == null) {
            return null;
        }
        if (Boolean.TYPE.equals(valueClass) || Boolean.class.equals(valueClass)) {
            return shape(GraphType.BOOLEAN, BooleanProperty.class);
        }
        if (Byte.TYPE.equals(valueClass) || Short.TYPE.equals(valueClass)
                || Integer.TYPE.equals(valueClass) || Byte.class.equals(valueClass)
                || Short.class.equals(valueClass) || Integer.class.equals(valueClass)) {
            return shape(GraphType.INT, IntegerProperty.class);
        }
        if (Long.TYPE.equals(valueClass) || Long.class.equals(valueClass)) {
            return shape(GraphType.LONG, LongProperty.class);
        }
        if (Float.TYPE.equals(valueClass) || Float.class.equals(valueClass)) {
            return shape(GraphType.FLOAT, FloatProperty.class);
        }
        if (Double.TYPE.equals(valueClass) || Double.class.equals(valueClass)) {
            return shape(GraphType.DOUBLE, DoubleProperty.class);
        }
        if (Character.TYPE.equals(valueClass) || Character.class.equals(valueClass)
                || CharSequence.class.isAssignableFrom(valueClass) || valueClass.isEnum()) {
            return shape(GraphType.STRING, StringProperty.class);
        }
        return null;
    }

    private static Shape shape(GraphType type, Class<?> propertyClass) {
        return new Shape(type, propertyClass.getName(), null);
    }

    static final class Declaration {
        private final String name;
        private final Shape shape;
        private final RepresentationSource source;

        Declaration(String name, Shape shape, RepresentationSource source) {
            this.name = name;
            this.shape = shape;
            this.source = source;
        }

        String name() {
            return name;
        }

        Shape shape() {
            return shape;
        }

        RepresentationSource source() {
            return source;
        }
    }

    static final class Shape {
        private final GraphType type;
        private final String propertyClass;
        private final String valueClass;

        Shape(GraphType type, String propertyClass, String valueClass) {
            this.type = type;
            this.propertyClass = propertyClass;
            this.valueClass = valueClass;
        }

        GraphType type() {
            return type;
        }

        String propertyClass() {
            return propertyClass;
        }

        String valueClass() {
            return valueClass;
        }
    }
}
