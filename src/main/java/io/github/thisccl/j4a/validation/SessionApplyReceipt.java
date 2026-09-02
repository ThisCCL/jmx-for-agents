package io.github.thisccl.j4a.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class SessionApplyReceipt {
    private final int appliedCount;
    private final List<CreatedRef> createdRefs;
    private final List<String> deletedRefs;
    private final Map<String, Object> auxiliaryResults;

    SessionApplyReceipt(int appliedCount, List<CreatedRef> createdRefs, List<String> deletedRefs) {
        this(appliedCount, createdRefs, deletedRefs, Collections.<String, Object>emptyMap());
    }

    SessionApplyReceipt(
            int appliedCount,
            List<CreatedRef> createdRefs,
            List<String> deletedRefs,
            Map<String, ?> auxiliaryResults) {
        if (appliedCount < 0) {
            throw new IllegalArgumentException("appliedCount must not be negative");
        }
        this.appliedCount = appliedCount;
        this.createdRefs = immutableCopy(createdRefs, "createdRefs");
        this.deletedRefs = immutableCopy(deletedRefs, "deletedRefs");
        this.auxiliaryResults = immutableMap(auxiliaryResults);
    }

    static SessionApplyReceipt dryRun(int appliedCount) {
        return new SessionApplyReceipt(
                appliedCount, Collections.<CreatedRef>emptyList(), Collections.<String>emptyList());
    }

    static SessionApplyReceipt dryRun(int appliedCount, Map<String, ?> auxiliaryResults) {
        return new SessionApplyReceipt(
                appliedCount, Collections.<CreatedRef>emptyList(), Collections.<String>emptyList(), auxiliaryResults);
    }

    int appliedCount() {
        return appliedCount;
    }

    List<CreatedRef> createdRefs() {
        return createdRefs;
    }

    List<String> deletedRefs() {
        return deletedRefs;
    }

    Map<String, Object> auxiliaryResults() {
        return auxiliaryResults;
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        List<T> copy = new ArrayList<T>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> values) {
        Objects.requireNonNull(values, "auxiliaryResults");
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "auxiliary result name"), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    static final class CreatedRef {
        private final String alias;
        private final String publicReference;

        CreatedRef(String alias, String publicReference) {
            this.alias = Objects.requireNonNull(alias, "alias");
            this.publicReference = Objects.requireNonNull(publicReference, "publicReference");
        }

        String alias() {
            return alias;
        }

        String publicReference() {
            return publicReference;
        }
    }
}
