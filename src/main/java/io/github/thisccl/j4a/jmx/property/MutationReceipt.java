package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class MutationReceipt {
    private final RuntimeContext runtimeContext;
    private final List<PropertyWrite> writes;
    private final List<RequiredContainer> requiredContainers;

    public MutationReceipt(RuntimeContext runtimeContext, List<PropertyWrite> writes) {
        this(runtimeContext, writes, Collections.<RequiredContainer>emptyList());
    }

    MutationReceipt(
            RuntimeContext runtimeContext,
            List<PropertyWrite> writes,
            List<RequiredContainer> requiredContainers) {
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtime context is required");
        this.writes = immutableWrites(writes);
        this.requiredContainers = immutableContainers(requiredContainers);
    }

    public RuntimeContext runtimeContext() {
        return runtimeContext;
    }

    public List<PropertyWrite> writes() {
        return writes;
    }

    List<RequiredContainer> requiredContainers() {
        return requiredContainers;
    }

    static List<PropertyWrite> immutableWrites(List<PropertyWrite> writes) {
        Objects.requireNonNull(writes, "property writes are required");
        ArrayList<PropertyWrite> copy = new ArrayList<PropertyWrite>(writes.size());
        for (PropertyWrite write : writes) {
            copy.add(Objects.requireNonNull(write, "property writes must not contain null"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<RequiredContainer> immutableContainers(
            List<RequiredContainer> requiredContainers) {
        Objects.requireNonNull(requiredContainers, "required containers are required");
        ArrayList<RequiredContainer> copy =
                new ArrayList<RequiredContainer>(requiredContainers.size());
        for (RequiredContainer requiredContainer : requiredContainers) {
            copy.add(Objects.requireNonNull(
                    requiredContainer, "required containers must not contain null"));
        }
        return Collections.unmodifiableList(copy);
    }

    static final class RequiredContainer {
        private final PropertyPath path;
        private final GraphPresence presence;
        private final String propertyClass;
        private final String elementClass;

        RequiredContainer(
                PropertyPath path,
                GraphPresence presence,
                String propertyClass,
                String elementClass) {
            this.path = Objects.requireNonNull(path, "container path is required");
            this.presence = Objects.requireNonNull(presence, "container presence is required");
            this.propertyClass = Objects.requireNonNull(
                    propertyClass, "container property class is required");
            this.elementClass = Objects.requireNonNull(
                    elementClass, "container element class is required");
        }

        PropertyPath path() {
            return path;
        }

        GraphPresence presence() {
            return presence;
        }

        String propertyClass() {
            return propertyClass;
        }

        String elementClass() {
            return elementClass;
        }
    }
}
