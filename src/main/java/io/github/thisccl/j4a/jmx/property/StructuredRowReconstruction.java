package io.github.thisccl.j4a.jmx.property;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;

final class StructuredRowReconstruction {
    enum Layout {
        DIRECT,
        WRAPPED
    }

    private final Constructor<?> rowConstructor;
    private final Method listMutator;
    private final JMeterProperty rowWrapperTemplate;

    StructuredRowReconstruction(Constructor<?> rowConstructor, Method listMutator) {
        this.rowConstructor = Objects.requireNonNull(
                rowConstructor, "row constructor is required");
        this.listMutator = Objects.requireNonNull(listMutator, "list mutator is required");
        this.rowWrapperTemplate = null;
    }

    private StructuredRowReconstruction(
            Constructor<?> rowConstructor, JMeterProperty rowWrapperTemplate) {
        this.rowConstructor = Objects.requireNonNull(
                rowConstructor, "row constructor is required");
        this.listMutator = null;
        this.rowWrapperTemplate = Objects.requireNonNull(
                rowWrapperTemplate, "row wrapper template is required");
    }

    static StructuredRowReconstruction direct(
            Constructor<?> rowConstructor, JMeterProperty rowWrapperTemplate) {
        return new StructuredRowReconstruction(rowConstructor, rowWrapperTemplate);
    }

    Constructor<?> rowConstructor() {
        return rowConstructor;
    }

    String listMutator() {
        return listMutator == null ? "direct_collection" : listMutator.getName();
    }

    Layout layout() {
        return listMutator == null ? Layout.DIRECT : Layout.WRAPPED;
    }

    JMeterProperty replace(JMeterProperty observed, List<TestElement> rows) {
        JMeterProperty property = cloneProperty(observed);
        if (layout() == Layout.DIRECT) {
            MultiProperty direct = (MultiProperty) property;
            direct.clear();
            for (TestElement row : rows) {
                JMeterProperty wrapper = cloneProperty(rowWrapperTemplate);
                wrapper.setName(row.getName());
                wrapper.setObjectValue(row);
                if (wrapper.getObjectValue() != row) {
                    throw error("observed direct row wrapper rejected the rebuilt row", null);
                }
                direct.addProperty(wrapper);
            }
            return property;
        }
        Object outerValue = observed.getObjectValue();
        Object outerCopy = ((TestElement) outerValue).clone();
        if (outerCopy == outerValue || outerCopy == null
                || outerCopy.getClass() != outerValue.getClass()) {
            throw error("observed outer row value cannot be cloned safely", null);
        }
        try {
            listMutator.invoke(outerCopy, rows);
        } catch (IllegalAccessException | InvocationTargetException | LinkageError exception) {
            throw error("observed row reconstruction operation failed", exception);
        }
        property.setObjectValue(outerCopy);
        return property;
    }

    private static JMeterProperty cloneProperty(JMeterProperty observed) {
        JMeterProperty copy = observed.clone();
        if (copy == observed || copy == null || copy.getClass() != observed.getClass()) {
            throw error("observed row property cannot be cloned safely", null);
        }
        return copy;
    }

    private static StructuredRowValueException error(String message, Throwable cause) {
        StructuredRowValueException failure = new StructuredRowValueException(message);
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }
}
