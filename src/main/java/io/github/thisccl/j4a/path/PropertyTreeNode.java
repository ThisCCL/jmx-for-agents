package io.github.thisccl.j4a.path;

import java.util.Optional;

public interface PropertyTreeNode {
    Optional<PropertyTreeNode> child(String name);

    Optional<PropertyTreeNode> element(int index);

    boolean scalar();

    PropertyValue value();

    void setValue(PropertyValue value);
}
