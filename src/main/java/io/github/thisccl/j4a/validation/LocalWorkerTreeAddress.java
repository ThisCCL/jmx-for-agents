package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

final class LocalWorkerTreeAddress {
    private LocalWorkerTreeAddress() {
    }

    static List<Integer> find(JmxTestPlan plan, TestElement target) {
        return find(logicalChildren(plan.tree()), target, new ArrayList<>());
    }

    static TestElement resolve(JmxTestPlan plan, List<Integer> address) {
        List<Node> siblings = logicalChildren(plan.tree());
        Node node = null;
        for (Integer index : address) {
            if (index == null || index < 0 || index >= siblings.size()) {
                throw new IllegalArgumentException("Mutation target address is out of range: " + address);
            }
            node = siblings.get(index);
            siblings = node.children;
        }
        if (node == null) {
            throw new IllegalArgumentException("Mutation target address is missing: " + address);
        }
        return node.element;
    }

    private static List<Integer> find(List<Node> siblings, TestElement target, List<Integer> prefix) {
        for (int index = 0; index < siblings.size(); index++) {
            Node node = siblings.get(index);
            List<Integer> address = new ArrayList<>(prefix);
            address.add(index);
            if (node.element == target) {
                return Collections.unmodifiableList(address);
            }
            List<Integer> nested = find(node.children, target, address);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static List<Node> logicalChildren(HashTree tree) {
        List<Node> result = new ArrayList<>();
        for (Object key : tree.list()) {
            HashTree children = tree.getTree(key);
            if (key instanceof TestElement) {
                result.add(new Node((TestElement) key, logicalChildren(children)));
            } else {
                result.addAll(logicalChildren(children));
            }
        }
        return result;
    }

    private static final class Node {
        private final TestElement element;
        private final List<Node> children;

        private Node(TestElement element, List<Node> children) {
            this.element = element;
            this.children = children;
        }
    }
}
