package io.github.thisccl.j4a.jmx.property;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;

final class RuntimeRowMetadataProof {
    private RuntimeRowMetadataProof() {
    }

    static Optional<EmptyRowEvidence> emptyEvidence(
            TestElement outer, MultiProperty observedStorage) {
        Optional<ListRelationship> relationship = listRelationship(outer, observedStorage);
        if (!relationship.isPresent()) {
            return Optional.empty();
        }
        Class<?> rowClass = relationship.get().rowClass;
        Optional<Constructor<?>> constructor = constructor(rowClass);
        if (!constructor.isPresent()) {
            return Optional.empty();
        }
        List<StructuredRowField> fields = RuntimePristineRowDefaults.prove(rowClass)
                .orElse(null);
        return fields == null ? Optional.<EmptyRowEvidence>empty()
                : Optional.of(new EmptyRowEvidence(rowClass, fields));
    }

    static Optional<StructuredRowReconstruction> reconstruction(
            TestElement outer, Class<?> rowClass) {
        Optional<Constructor<?>> constructor = constructor(rowClass);
        Method selected = null;
        for (Method method : outer.getClass().getMethods()) {
            Class<?> acceptedRowClass = listMutatorRowClass(method);
            if (acceptedRowClass != null && acceptedRowClass.isAssignableFrom(rowClass)) {
                if (selected != null) {
                    return Optional.empty();
                }
                selected = method;
            }
        }
        return constructor.isPresent() && selected != null
                ? Optional.of(new StructuredRowReconstruction(constructor.get(), selected))
                : Optional.empty();
    }

    static Optional<StructuredRowReconstruction> directReconstruction(
            Class<?> rowClass, JMeterProperty rowWrapper) {
        Optional<Constructor<?>> constructor = constructor(rowClass);
        if (!constructor.isPresent()) {
            return Optional.empty();
        }
        try {
            Object row = constructor.get().newInstance();
            JMeterProperty wrapper = rowWrapper.clone();
            if (wrapper == rowWrapper || wrapper == null
                    || wrapper.getClass() != rowWrapper.getClass()) {
                return Optional.empty();
            }
            wrapper.setObjectValue(row);
            return wrapper.getObjectValue() == row
                    ? Optional.of(StructuredRowReconstruction.direct(
                            constructor.get(), rowWrapper))
                    : Optional.<StructuredRowReconstruction>empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private static Optional<ListRelationship> listRelationship(
            TestElement outer, MultiProperty observedStorage) {
        Class<?> outerClass = outer.getClass();
        Method setter = null;
        Class<?> rowClass = null;
        for (Method method : outerClass.getMethods()) {
            Class<?> candidate = listMutatorRowClass(method);
            if (candidate != null) {
                if (setter != null) {
                    return Optional.empty();
                }
                setter = method;
                rowClass = candidate;
            }
        }
        if (setter == null) {
            return Optional.empty();
        }
        if (overloadedSetter(outerClass, setter.getName())) {
            return Optional.empty();
        }
        String stem = setter.getName().substring("set".length());
        String accessorName = "get" + stem;
        Method accessor = null;
        for (Method method : outerClass.getMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && method.getParameterTypes().length == 0
                    && org.apache.jmeter.testelement.property.MultiProperty.class
                            .isAssignableFrom(method.getReturnType())
                    && (method.getName().equals(accessorName)
                            || method.getName().equals(accessorName + "Collection"))) {
                if (accessor != null) {
                    return Optional.empty();
                }
                accessor = method;
            }
        }
        if (accessor == null) {
            return Optional.empty();
        }
        try {
            return accessor.invoke(outer) == observedStorage
                    ? Optional.of(new ListRelationship(rowClass))
                    : Optional.<ListRelationship>empty();
        } catch (IllegalAccessException | InvocationTargetException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private static boolean overloadedSetter(Class<?> outerClass, String setterName) {
        int matches = 0;
        for (Method method : outerClass.getMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && method.getName().equals(setterName)
                    && method.getParameterTypes().length == 1) {
                matches++;
            }
        }
        return matches != 1;
    }

    private static Class<?> genericListRowClass(Class<?> rawClass, Type genericType) {
        if (rawClass != List.class || !(genericType instanceof ParameterizedType)) {
            return null;
        }
        Type[] arguments = ((ParameterizedType) genericType).getActualTypeArguments();
        return arguments.length == 1 && arguments[0] instanceof Class<?>
                ? (Class<?>) arguments[0] : null;
    }

    private static Class<?> listMutatorRowClass(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || method.getParameterTypes().length != 1
                || method.getParameterTypes()[0] != List.class) {
            return null;
        }
        return genericListRowClass(
                method.getParameterTypes()[0], method.getGenericParameterTypes()[0]);
    }

    private static Optional<Constructor<?>> constructor(Class<?> rowClass) {
        if (!TestElement.class.isAssignableFrom(rowClass)
                || rowClass.isInterface() || Modifier.isAbstract(rowClass.getModifiers())) {
            return Optional.empty();
        }
        try {
            return Optional.<Constructor<?>>of(rowClass.getConstructor());
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
    }

    static final class EmptyRowEvidence {
        private final Class<?> rowClass;
        private final List<StructuredRowField> fields;

        private EmptyRowEvidence(Class<?> rowClass, List<StructuredRowField> fields) {
            this.rowClass = rowClass;
            this.fields = fields;
        }

        Class<?> rowClass() { return rowClass; }
        List<StructuredRowField> fields() { return fields; }
    }

    private static final class ListRelationship {
        private final Class<?> rowClass;

        private ListRelationship(Class<?> rowClass) {
            this.rowClass = rowClass;
        }
    }
}
