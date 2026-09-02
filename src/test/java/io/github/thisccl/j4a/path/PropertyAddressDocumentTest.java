package io.github.thisccl.j4a.path;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PropertyAddressDocumentTest {
    @Test
    void convertsTypedInternalPathsToScalarArraysOnly() {
        PropertyPath path = new PropertyPath(Arrays.asList(
                PropertyPathSegment.property("literal.dot"),
                PropertyPathSegment.index(3),
                PropertyPathSegment.key("key[part]\\tail")));

        assertThat(PropertyAddressDocument.scalarSegments(path))
                .containsExactly("literal.dot", Integer.valueOf(3), "key[part]\\tail");
    }

    @Test
    void exposesOnlyTypedPathToScalarArrayEncoding() {
        assertThat(Arrays.stream(PropertyAddressDocument.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("scalarSegments");
    }
}
