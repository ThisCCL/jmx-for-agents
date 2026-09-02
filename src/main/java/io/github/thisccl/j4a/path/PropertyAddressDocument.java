package io.github.thisccl.j4a.path;

import java.util.List;

public final class PropertyAddressDocument {
    private PropertyAddressDocument() {
    }

    public static List<Object> scalarSegments(PropertyPath path) {
        return PropertyAddress.fromPath(path).segments();
    }
}
