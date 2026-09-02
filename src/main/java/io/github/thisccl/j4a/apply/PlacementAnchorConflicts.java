package io.github.thisccl.j4a.apply;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

public final class PlacementAnchorConflicts<T> {
    private final Set<T> anchors = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<T> relocated = Collections.newSetFromMap(new IdentityHashMap<>());

    public void recordAnchor(Optional<T> anchor) {
        anchor.ifPresent(anchors::add);
    }

    public void recordRelocated(T element) {
        relocated.add(element);
    }

    public Optional<T> conflictingAnchor() {
        return anchors.stream().filter(relocated::contains).findFirst();
    }
}
