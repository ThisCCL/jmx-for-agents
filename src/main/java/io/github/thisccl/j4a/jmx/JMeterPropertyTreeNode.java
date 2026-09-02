package io.github.thisccl.j4a.jmx;

import io.github.thisccl.j4a.path.PropertyTreeNode;
import io.github.thisccl.j4a.path.PropertyValue;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;

public final class JMeterPropertyTreeNode implements PropertyTreeNode {
    private final TestElement element;
    private final JMeterProperty property;
    private final Consumer<JMeterProperty> replacement;

    private JMeterPropertyTreeNode(TestElement element, JMeterProperty property, Consumer<JMeterProperty> replacement) {
        this.element = element;
        this.property = property;
        this.replacement = replacement;
    }

    public static JMeterPropertyTreeNode root(TestElement element) {
        return new JMeterPropertyTreeNode(element, null, ignored -> { });
    }

    @Override
    public Optional<PropertyTreeNode> child(String name) {
        TestElement childElement = childElement();
        if (childElement == null) {
            return Optional.empty();
        }

        JMeterProperty childProperty = childElement.getPropertyOrNull(name);
        if (childProperty == null) {
            return Optional.empty();
        }
        return Optional.of(new JMeterPropertyTreeNode(null, childProperty, childElement::setProperty));
    }

    @Override
    public Optional<PropertyTreeNode> element(int index) {
        if (!(property instanceof CollectionProperty) || index < 0 || index >= ((CollectionProperty) property).size()) {
            return Optional.empty();
        }
        CollectionProperty collectionProperty = (CollectionProperty) property;
        return Optional.of(new JMeterPropertyTreeNode(null, collectionProperty.get(index), value -> collectionProperty.set(index, value)));
    }

    @Override
    public boolean scalar() {
        return property != null && !(property instanceof CollectionProperty) && !(property instanceof TestElementProperty);
    }

    @Override
    public PropertyValue value() {
        if (!scalar()) {
            return null;
        }
        return PropertyValue.string(property.getStringValue());
    }

    @Override
    public void setValue(PropertyValue value) {
        if (!scalar()) {
            throw new IllegalStateException("Cannot set a non-scalar JMeter property");
        }
        replacement.accept(toJMeterProperty(property.getName(), value));
    }

    private TestElement childElement() {
        if (element != null) {
            return element;
        }
        if (property instanceof TestElementProperty) {
            return ((TestElementProperty) property).getElement();
        }
        return null;
    }

    private static JMeterProperty toJMeterProperty(String name, PropertyValue value) {
        switch (value.type()) {
            case STRING:
            case RAW:
                return new StringProperty(name, value.text());
            case BOOLEAN:
                return new BooleanProperty(name, (Boolean) value.value());
            case INT:
                return new IntegerProperty(name, (Integer) value.value());
            case LONG:
                return new LongProperty(name, (Long) value.value());
            case DOUBLE:
                return new DoubleProperty(name, (Double) value.value());
            default:
                throw new IllegalArgumentException("Unsupported property value type: " + value.type());
        }
    }
}
