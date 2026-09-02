package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class RuntimeRowObservation {
    private final String outerPropertyClass;
    private final String outerValueClass;
    private final String storagePropertyClass;
    private final String rowWrapperClass;
    private final String rowClass;
    private final List<StructuredRowField> fields;
    private final boolean emptyEvidence;
    private final StructuredRowReconstruction reconstruction;

    RuntimeRowObservation(
            String outerPropertyClass,
            String outerValueClass,
            String storagePropertyClass,
            String rowWrapperClass,
            String rowClass,
            List<StructuredRowField> fields,
            boolean emptyEvidence,
            StructuredRowReconstruction reconstruction) {
        this.outerPropertyClass = Objects.requireNonNull(outerPropertyClass);
        this.outerValueClass = Objects.requireNonNull(outerValueClass);
        this.storagePropertyClass = Objects.requireNonNull(storagePropertyClass);
        this.rowWrapperClass = rowWrapperClass;
        this.rowClass = Objects.requireNonNull(rowClass);
        this.fields = Collections.unmodifiableList(new ArrayList<StructuredRowField>(fields));
        this.emptyEvidence = emptyEvidence;
        this.reconstruction = Objects.requireNonNull(reconstruction);
    }

    String outerPropertyClass() { return outerPropertyClass; }
    String outerValueClass() { return outerValueClass; }
    String storagePropertyClass() { return storagePropertyClass; }
    Optional<String> rowWrapperClass() { return Optional.ofNullable(rowWrapperClass); }
    String rowClass() { return rowClass; }
    List<StructuredRowField> fields() { return fields; }
    boolean emptyEvidence() { return emptyEvidence; }
    StructuredRowReconstruction reconstruction() { return reconstruction; }
}
