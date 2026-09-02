package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.components.ComponentKindNormalizer;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.locator.LocatorNode;
import io.github.thisccl.j4a.locator.StructuralLocator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

final class JmxLocatorTree {
    private JmxLocatorTree() {
    }

    static TestElement findElement(JmxTestPlan testPlan, String locator) {
        List<CliLocatorNode> roots = roots(testPlan.tree());
        for (CliLocatorNode root : roots) {
            try {
                LocatorNode located = StructuralLocator.defaultLocator().locate(root).requireUnique(locator);
                return ((CliLocatorNode) located).element();
            } catch (IllegalArgumentException ignored) {
            }
        }
        throw new IllegalArgumentException("Unknown locator: " + locator);
    }

    static List<CliLocatorNode> roots(HashTree tree) {
        List<CliLocatorNode> roots = new ArrayList<>();
        for (Object node : tree.list()) {
            if (node instanceof TestElement) {
                TestElement testElement = (TestElement) node;
                roots.add(new CliLocatorNode(testElement, null, roots(tree.getTree(node))));
            } else {
                roots.addAll(roots(tree.getTree(node)));
            }
        }
        return roots;
    }

    static String componentKind(String componentClass) {
        return ComponentKindNormalizer.componentKind(componentClass);
    }
}

final class CliLocatorNode implements LocatorNode {
    private final TestElement element;
    private final CliLocatorNode parent;
    private final List<CliLocatorNode> children;

    CliLocatorNode(TestElement element, CliLocatorNode parent, List<CliLocatorNode> children) {
        this.element = element;
        this.parent = parent;
        List<CliLocatorNode> attachedChildren = new ArrayList<>();
        for (CliLocatorNode child : children) {
            attachedChildren.add(new CliLocatorNode(child.element(), this, child.childNodes()));
        }
        this.children = Collections.unmodifiableList(attachedChildren);
    }

    TestElement element() {
        return element;
    }

    CliLocatorNode parent() {
        return parent;
    }

    List<CliLocatorNode> childNodes() {
        return children;
    }

    @Override
    public String componentClass() {
        return element.getClass().getSimpleName();
    }

    @Override
    public List<? extends LocatorNode> children() {
        return children;
    }

    @Override
    public String displayName() {
        return element.getName();
    }
}
