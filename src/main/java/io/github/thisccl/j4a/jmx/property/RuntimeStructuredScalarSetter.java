package io.github.thisccl.j4a.jmx.property;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;

final class RuntimeStructuredScalarSetter {
    private final String canonicalProperty;
    private final Method setter;
    private final List<String> footprint;

    private RuntimeStructuredScalarSetter(
            String canonicalProperty, Method setter, List<String> footprint) {
        this.canonicalProperty = canonicalProperty;
        this.setter = setter;
        this.footprint = Collections.unmodifiableList(new ArrayList<String>(footprint));
    }

    static RuntimeStructuredScalarSetter prove(
            Class<?> rowClass,
            String canonicalProperty,
            String setterName,
            Class<?> inputType,
            List<String> footprint) {
        Objects.requireNonNull(rowClass, "row class is required");
        Objects.requireNonNull(inputType, "setter input type is required");
        if (canonicalProperty == null || canonicalProperty.isEmpty()
                || setterName == null || setterName.isEmpty()
                || footprint.isEmpty() || !canonicalProperty.equals(footprint.get(0))) {
            throw new IllegalArgumentException("runtime scalar setter evidence is incomplete");
        }
        if (!setterName.startsWith("set") || setterName.length() == 3) {
            throw new IllegalArgumentException(
                    "runtime scalar setter name is not a setter for " + canonicalProperty);
        }
        Method selected = null;
        for (Method method : rowClass.getMethods()) {
            if (setterName.equals(method.getName())
                    && method.getReturnType() == Void.TYPE
                    && method.getParameterTypes().length == 1
                    && accepts(method.getParameterTypes()[0], inputType)) {
                if (selected != null) {
                    throw new IllegalArgumentException(
                            "runtime scalar setter evidence is ambiguous for " + canonicalProperty);
                }
                selected = method;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException(
                    "runtime scalar setter is unavailable for " + canonicalProperty);
        }
        return new RuntimeStructuredScalarSetter(canonicalProperty, selected, footprint);
    }

    String canonicalProperty() {
        return canonicalProperty;
    }

    List<String> footprint() {
        return footprint;
    }

    boolean declaredBy(Class<?> rowClass) {
        return setter.getDeclaringClass() == rowClass;
    }

    boolean derivedFrom(Map<?, ?> submitted, String property) {
        return submitted.containsKey(canonicalProperty)
                && !canonicalProperty.equals(property)
                && footprint.contains(property);
    }

    void rejectConflict(
            Map<?, ?> submitted,
            List<RuntimeStructuredScalarSetter> allSetters,
            int rowIndex) {
        if (!submitted.containsKey(canonicalProperty)) return;
        for (String property : footprint) {
            if (!canonicalProperty.equals(property)
                    && submitted.containsKey(property)
                    && !hasCanonicalSetter(allSetters, property)) {
                throw new StructuredRowValueException(
                        "rows[" + rowIndex + "]." + property
                                + " conflicts with canonical setter footprint for '"
                                + canonicalProperty + "'");
            }
        }
    }

    private static boolean hasCanonicalSetter(
            List<RuntimeStructuredScalarSetter> setters, String property) {
        for (RuntimeStructuredScalarSetter setter : setters) {
            if (setter.canonicalProperty.equals(property)) return true;
        }
        return false;
    }

    void write(TestElement row, Object value) {
        try {
            setter.invoke(row, value);
        } catch (IllegalAccessException | InvocationTargetException | LinkageError exception) {
            StructuredRowValueException failure = new StructuredRowValueException(
                    "runtime scalar setter failed for '" + canonicalProperty + "'");
            failure.initCause(exception);
            throw failure;
        }
    }

    private static boolean accepts(Class<?> parameter, Class<?> input) {
        if (parameter.isAssignableFrom(input)) return true;
        return parameter == Boolean.TYPE && input == Boolean.class
                || parameter == Integer.TYPE && input == Integer.class
                || parameter == Long.TYPE && input == Long.class
                || parameter == Float.TYPE && input == Float.class
                || parameter == Double.TYPE && input == Double.class;
    }
}
