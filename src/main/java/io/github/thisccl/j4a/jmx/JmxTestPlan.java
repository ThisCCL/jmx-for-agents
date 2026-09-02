package io.github.thisccl.j4a.jmx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

public final class JmxTestPlan {
    private final ListedHashTree tree;
    private final JmxSourceLineIndex sourceLineIndex;

    public JmxTestPlan(ListedHashTree tree) {
        this(tree, JmxSourceLineIndex.empty());
    }

    public JmxTestPlan(ListedHashTree tree, JmxSourceLineIndex sourceLineIndex) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.sourceLineIndex = Objects.requireNonNull(sourceLineIndex, "sourceLineIndex");
    }

    public ListedHashTree tree() {
        return tree;
    }

    public JmxSourceLineIndex sourceLineIndex() {
        return sourceLineIndex;
    }

    public List<TestElement> depthFirstTestElements() {
        List<TestElement> elements = new ArrayList<>();
        collectTestElements(tree, elements);
        return Collections.unmodifiableList(elements);
    }

    public List<String> depthFirstTestElementNames() {
        return depthFirstTestElements().stream()
                .map(TestElement::getName)
                .collect(java.util.stream.Collectors.toList());
    }

    private static void collectTestElements(HashTree currentTree, List<TestElement> elements) {
        for (Object node : currentTree.list()) {
            if (node instanceof TestElement) {
                elements.add((TestElement) node);
            }
            collectTestElements(currentTree.getTree(node), elements);
        }
    }
}
