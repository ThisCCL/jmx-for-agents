package io.github.thisccl.j4a.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class PropertyPathTest {
    @Test
    void rejectsNullSegmentListAtConstruction() {
        assertThatThrownBy(() -> new PropertyPath(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("property path segments are required");
    }

    @Test
    void rejectsNullSegmentAtItsIndexedPositionAtConstruction() {
        assertThatThrownBy(() -> new PropertyPath(Arrays.asList(
                        PropertyPathSegment.property("root"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("property path segment at index 1 is required");
    }

    @Test
    void rejectsNullSegmentKindAtConstruction() {
        assertThatThrownBy(() -> new PropertyPathSegment(null, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("property path segment kind is required");
    }

    @Test
    void rejectsNullPropertyNameAtConstruction() {
        assertThatThrownBy(() -> new PropertyPathSegment(
                        PropertyPathSegment.Kind.PROPERTY, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("property path segment name is required");
    }

    @Test
    void acceptsEmptyTypedPropertyNameAtConstruction() {
        assertThat(new PropertyPathSegment(PropertyPathSegment.Kind.PROPERTY, "", -1))
                .isEqualTo(PropertyPathSegment.property(""));
    }

    @Test
    void rejectsPropertySegmentWithNonSentinelIndexAtConstruction() {
        assertThatThrownBy(() -> new PropertyPathSegment(
                        PropertyPathSegment.Kind.PROPERTY, "name", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("property segment index must be -1");
    }

    @Test
    void rejectsIndexSegmentWithNameAtConstruction() {
        assertThatThrownBy(() -> new PropertyPathSegment(
                        PropertyPathSegment.Kind.INDEX, "name", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("index segment name must be null");
    }

    @Test
    void rejectsNegativeIndexSegmentAtConstruction() {
        assertThatThrownBy(() -> new PropertyPathSegment(
                        PropertyPathSegment.Kind.INDEX, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("collection index must be zero or greater");
    }

    @Test
    void retainsExplicitTypedSegmentsWithoutInterpretingPunctuation() {
        PropertyPath path = new PropertyPath(Arrays.asList(
                PropertyPathSegment.property("Arguments.arguments"),
                PropertyPathSegment.index(0),
                PropertyPathSegment.key("key.with.dot"),
                PropertyPathSegment.property("escaped.name"),
                PropertyPathSegment.property("bracket[key]\\tail")));

        assertThat(path.segments()).containsExactly(
                PropertyPathSegment.property("Arguments.arguments"),
                PropertyPathSegment.index(0),
                PropertyPathSegment.key("key.with.dot"),
                PropertyPathSegment.property("escaped.name"),
                PropertyPathSegment.property("bracket[key]\\tail"));
    }

    @Test
    void copiesAndExposesAnImmutableSegmentList() {
        ArrayList<PropertyPathSegment> source = new ArrayList<PropertyPathSegment>();
        source.add(PropertyPathSegment.property("root"));

        PropertyPath path = new PropertyPath(source);
        source.add(PropertyPathSegment.property("later"));

        assertThat(path.segments()).containsExactly(PropertyPathSegment.property("root"));
        assertThatThrownBy(() -> path.segments().add(PropertyPathSegment.property("blocked")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsPropertyAndKeySegmentsSemanticallyDistinct() {
        PropertyPathSegment named = PropertyPathSegment.property("Content-Type");
        PropertyPathSegment key = PropertyPathSegment.key("Content-Type");

        assertThat(named).isNotEqualTo(key);
        assertThat(new HashSet<>(Arrays.asList(named, key))).hasSize(2);
        assertThat(named.kind()).isEqualTo(PropertyPathSegment.Kind.PROPERTY);
        assertThat(key.kind()).isEqualTo(PropertyPathSegment.Kind.KEY);
    }

    @Test
    void equalityAndHashCodeFollowTypedSegmentOrder() {
        PropertyPath first = TestPropertyPaths.properties("root", Integer.valueOf(0), "leaf");
        PropertyPath same = TestPropertyPaths.properties("root", Integer.valueOf(0), "leaf");
        PropertyPath different = TestPropertyPaths.properties("root", Integer.valueOf(1), "leaf");

        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(different);
    }
}
