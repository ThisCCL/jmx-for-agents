package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class GraphNodeProvenance {
    private final GraphCapability capability;
    private final List<RepresentationSource> sources;

    GraphNodeProvenance(
            GraphCapability capability, List<RepresentationSource> provenance) {
        this.capability = Objects.requireNonNull(capability, "graph capability is required");
        Objects.requireNonNull(provenance, "graph provenance is required");
        ArrayList<RepresentationSource> ordered =
                new ArrayList<RepresentationSource>(provenance.size());
        for (RepresentationSource source : provenance) {
            RepresentationSource required = Objects.requireNonNull(
                    source, "graph provenance must not contain null");
            if (!ordered.contains(required)) {
                ordered.add(required);
            }
        }
        if (ordered.isEmpty() || ordered.get(0) != capability.representationSource()) {
            throw new IllegalArgumentException(
                    "graph provenance must start with the representation source");
        }
        this.sources = Collections.unmodifiableList(ordered);
    }

    GraphCapability capability() {
        return capability;
    }

    List<RepresentationSource> sources() {
        return sources;
    }
}
