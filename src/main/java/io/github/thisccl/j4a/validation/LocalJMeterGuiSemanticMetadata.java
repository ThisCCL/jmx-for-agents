package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowEvidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class LocalJMeterGuiSemanticMetadata {
    static final String SUPPORTED_JMETER_VERSION = "5.6.3";
    static final String DISABLED_PROPERTY = "j4a.guiSemanticEvidence.disabled";

    private LocalJMeterGuiSemanticMetadata() {
    }

    static boolean disabled() {
        return Boolean.parseBoolean(System.getProperty(DISABLED_PROPERTY, "false"));
    }

    enum FailureReason {
        UNSUPPORTED_VERSION,
        FOREIGN_CLASSLOADER,
        TOP_LEVEL_WINDOW,
        INACCESSIBLE_FIELD,
        UNSUPPORTED_BINDING,
        OPAQUE_DESCRIPTOR,
        CONFLICTING_DESCRIPTOR,
        DEPTH_BUDGET,
        OBJECT_BUDGET,
        FIELD_BUDGET,
        DESCRIPTOR_BUDGET,
        TABLE_CANDIDATE_BUDGET,
        OUTPUT_BUDGET,
        ELAPSED_TIME_BUDGET,
        TABLE_MODEL_SHAPE,
        ROW_CONSTRUCTOR,
        SENTINEL_UNAVAILABLE,
        CANDIDATE_AMBIGUOUS,
        PROBE_NO_OP,
        PROBE_AMBIGUOUS,
        ROW_RECONSTRUCTION
    }

    enum SourceStatus {
        RUNTIME_PROVEN,
        RUNTIME_METADATA_UNAVAILABLE
    }

    static final class Failure {
        private final FailureReason reason;
        private final String detail;

        Failure(FailureReason reason, String detail) {
            this.reason = Objects.requireNonNull(reason, "reason");
            this.detail = detail == null ? "" : detail;
        }

        FailureReason reason() {
            return reason;
        }

        String detail() {
            return detail;
        }
    }

    static final class ScalarDescriptor {
        private final String property;
        private final String type;
        private final Object defaultValue;

        ScalarDescriptor(String property, String type, Object defaultValue) {
            this.property = Objects.requireNonNull(property, "property");
            this.type = Objects.requireNonNull(type, "type");
            this.defaultValue = immutableScalar(defaultValue);
        }

        String property() {
            return property;
        }

        String type() {
            return type;
        }

        Object defaultValue() {
            return defaultValue;
        }

        private static Object immutableScalar(Object value) {
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof Character || value instanceof Enum) {
                return value;
            }
            return String.valueOf(value);
        }
    }

    static final class StructuredRowConsumer {
        private final String property;
        private final String exactRowClass;
        private final List<String> rowProperties;
        private final String reconstructionShape;
        private final RuntimeStructuredRowEvidence evidence;

        StructuredRowConsumer(
                String property, String exactRowClass, List<String> rowProperties, String reconstructionShape) {
            this(property, exactRowClass, rowProperties, reconstructionShape, null);
        }

        StructuredRowConsumer(
                String property,
                String exactRowClass,
                List<String> rowProperties,
                String reconstructionShape,
                RuntimeStructuredRowEvidence evidence) {
            this.property = Objects.requireNonNull(property, "property");
            this.exactRowClass = Objects.requireNonNull(exactRowClass, "exactRowClass");
            this.rowProperties = Collections.unmodifiableList(new ArrayList<>(rowProperties));
            this.reconstructionShape = Objects.requireNonNull(reconstructionShape, "reconstructionShape");
            this.evidence = evidence;
        }

        String property() { return property; }
        String exactRowClass() { return exactRowClass; }
        List<String> rowProperties() { return rowProperties; }
        String reconstructionShape() { return reconstructionShape; }
        RuntimeStructuredRowEvidence evidence() { return evidence; }
    }

    static final class Stats {
        private final int visitedObjects;
        private final int reflectedFields;
        private final int maximumDepth;
        private final int descriptorCandidates;
        private final int tableCandidates;
        private final long elapsedNanos;

        Stats(int visitedObjects, int reflectedFields, int maximumDepth, int descriptorCandidates,
                int tableCandidates, long elapsedNanos) {
            this.visitedObjects = visitedObjects;
            this.reflectedFields = reflectedFields;
            this.maximumDepth = maximumDepth;
            this.descriptorCandidates = descriptorCandidates;
            this.tableCandidates = tableCandidates;
            this.elapsedNanos = elapsedNanos;
        }

        int visitedObjects() { return visitedObjects; }
        int reflectedFields() { return reflectedFields; }
        int maximumDepth() { return maximumDepth; }
        int descriptorCandidates() { return descriptorCandidates; }
        int tableCandidates() { return tableCandidates; }
        long elapsedNanos() { return elapsedNanos; }
    }

    static final class Observation {
        private final List<ScalarDescriptor> scalarDescriptors;
        private final List<StructuredRowConsumer> structuredRowConsumers;
        private final List<Failure> failures;
        private final Stats stats;

        Observation(List<ScalarDescriptor> scalarDescriptors, List<Failure> failures, Stats stats) {
            this(scalarDescriptors, Collections.<StructuredRowConsumer>emptyList(), failures, stats);
        }

        Observation(
                List<ScalarDescriptor> scalarDescriptors,
                List<StructuredRowConsumer> structuredRowConsumers,
                List<Failure> failures,
                Stats stats) {
            this.scalarDescriptors = Collections.unmodifiableList(new ArrayList<>(scalarDescriptors));
            this.structuredRowConsumers = Collections.unmodifiableList(new ArrayList<>(structuredRowConsumers));
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.stats = Objects.requireNonNull(stats, "stats");
        }

        List<ScalarDescriptor> scalarDescriptors() { return scalarDescriptors; }
        List<StructuredRowConsumer> structuredRowConsumers() { return structuredRowConsumers; }
        List<Failure> failures() { return failures; }
        Stats stats() { return stats; }
        SourceStatus status() {
            return failures.isEmpty()
                    ? SourceStatus.RUNTIME_PROVEN : SourceStatus.RUNTIME_METADATA_UNAVAILABLE;
        }
        boolean runtimeProven() { return status() == SourceStatus.RUNTIME_PROVEN; }

        static Observation provenEmpty() {
            return new Observation(Collections.<ScalarDescriptor>emptyList(),
                    Collections.<Failure>emptyList(), new Stats(0, 0, 0, 0, 0, 0L));
        }
    }

    static final class Budget {
        /*
         * The 83-component JMeter 5.6.3 characterization maxima were depth 7,
         * 214 objects, 188 fields, 25 descriptors, 6 tables, and 25 output rows.
         * Fixed limits retain at least 2x headroom and bound malformed plugin graphs.
         */
        static final Budget CORE_5_6_3 = new Budget(16, 1024, 1024, 128, 32, 128, 2_000_000_000L);

        final int maxDepth;
        final int maxObjects;
        final int maxFields;
        final int maxDescriptors;
        final int maxTableCandidates;
        final int maxOutputRows;
        final long maxElapsedNanos;

        Budget(int maxDepth, int maxObjects, int maxFields, int maxDescriptors,
                int maxTableCandidates, int maxOutputRows, long maxElapsedNanos) {
            this.maxDepth = maxDepth;
            this.maxObjects = maxObjects;
            this.maxFields = maxFields;
            this.maxDescriptors = maxDescriptors;
            this.maxTableCandidates = maxTableCandidates;
            this.maxOutputRows = maxOutputRows;
            this.maxElapsedNanos = maxElapsedNanos;
        }
    }
}
