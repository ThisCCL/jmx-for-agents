package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.locator.LocatorIndex;
import io.github.thisccl.j4a.locator.LocatorNode;
import io.github.thisccl.j4a.locator.StructuralLocator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

final class SessionPlanIndex {
    private final Map<TestElement, LocatedElement> byElement;
    private final Map<String, LocatedElement> byLocator;
    private final Set<String> ambiguousLocators;
    private final List<LocatedElement> orderedElements;

    private SessionPlanIndex(
            Map<TestElement, LocatedElement> byElement,
            Map<String, LocatedElement> byLocator,
            Set<String> ambiguousLocators,
            List<LocatedElement> orderedElements) {
        this.byElement = byElement;
        this.byLocator = byLocator;
        this.ambiguousLocators = ambiguousLocators;
        this.orderedElements = orderedElements;
    }

    static SessionPlanIndex create(JmxTestPlan plan) {
        Map<TestElement, LocatedElement> byElement = new IdentityHashMap<TestElement, LocatedElement>();
        Map<String, LocatedElement> byLocator = new LinkedHashMap<String, LocatedElement>();
        Set<String> ambiguousLocators = new HashSet<String>();
        List<LocatedElement> orderedElements = new ArrayList<LocatedElement>();
        for (PlanNode root : roots(plan.tree())) {
            LocatorIndex locators = StructuralLocator.defaultLocator().locate(root);
            index(root, locators, byElement, byLocator, ambiguousLocators, orderedElements);
        }
        return new SessionPlanIndex(
                byElement, byLocator, ambiguousLocators, Collections.unmodifiableList(orderedElements));
    }

    private static void index(
            PlanNode node,
            LocatorIndex locators,
            Map<TestElement, LocatedElement> byElement,
            Map<String, LocatedElement> byLocator,
            Set<String> ambiguousLocators,
            List<LocatedElement> orderedElements) {
        String locator = locators.locatorFor(node);
        LocatedElement located = new LocatedElement(node.element, locator);
        byElement.put(node.element, located);
        orderedElements.add(located);
        if (byLocator.put(locator, located) != null) {
            ambiguousLocators.add(locator);
            byLocator.remove(locator);
        }
        for (PlanNode child : node.children) {
            index(child, locators, byElement, byLocator, ambiguousLocators, orderedElements);
        }
    }

    LocatedElement require(TestElement element) {
        LocatedElement located = byElement.get(Objects.requireNonNull(element, "node"));
        if (located == null || ambiguousLocators.contains(located.locator)) {
            throw new IllegalArgumentException("Node is not uniquely part of this bound request");
        }
        return located;
    }

    LocatedElement find(String locator) {
        return ambiguousLocators.contains(locator) ? null : byLocator.get(locator);
    }

    LocatedElement find(TestElement element) {
        return byElement.get(element);
    }

    List<LocatedElement> orderedElements() {
        return orderedElements;
    }

    private static List<PlanNode> roots(HashTree tree) {
        List<PlanNode> roots = new ArrayList<PlanNode>();
        for (Object node : tree.list()) {
            HashTree subtree = tree.getTree(node);
            if (node instanceof TestElement) {
                roots.add(new PlanNode((TestElement) node, roots(subtree)));
            } else {
                roots.addAll(roots(subtree));
            }
        }
        return roots;
    }

    static final class LocatedElement {
        private final TestElement element;
        private final String locator;

        private LocatedElement(TestElement element, String locator) {
            this.element = element;
            this.locator = locator;
        }

        TestElement element() {
            return element;
        }

        String locator() {
            return locator;
        }
    }

    private static final class PlanNode implements LocatorNode {
        private final TestElement element;
        private final List<PlanNode> children;

        private PlanNode(TestElement element, List<PlanNode> children) {
            this.element = element;
            this.children = Collections.unmodifiableList(new ArrayList<PlanNode>(children));
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
}
