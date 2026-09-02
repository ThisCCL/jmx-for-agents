package io.github.thisccl.j4a.locator;

import java.util.List;
import java.util.Collections;

public interface LocatorNode {
    String componentClass();

    List<? extends LocatorNode> children();

    default String displayName() {
        return "";
    }

    default boolean enabled() {
        return true;
    }

    default String samplerDomain() {
        return "";
    }

    default String samplerPath() {
        return "";
    }

    default List<LocatorHeader> headers() {
        return Collections.emptyList();
    }

    default String value() {
        return "";
    }
}
