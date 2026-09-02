package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.locator.LocatorIndex;
import io.github.thisccl.j4a.locator.LocatorNode;
import io.github.thisccl.j4a.locator.LocatorNotFoundException;
import io.github.thisccl.j4a.locator.StructuralLocator;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

final class SnapshotReferenceSpace {
    BoundReferences bind(JmxTestPlan loadedPlan) {
        return SnapshotBoundReferences.bind(Objects.requireNonNull(loadedPlan, "loadedPlan"));
    }

    private static final class SnapshotBoundReferences implements ExactBoundReferences {
        private final List<RootBinding> roots;
        private final Map<TestElement, String> addressesByElement;

        private SnapshotBoundReferences(List<RootBinding> roots, Map<TestElement, String> addressesByElement) {
            this.roots = roots;
            this.addressesByElement = addressesByElement;
        }

        private static SnapshotBoundReferences bind(JmxTestPlan loadedPlan) {
            List<RootBinding> roots = new ArrayList<>();
            Map<TestElement, String> addresses = new IdentityHashMap<TestElement, String>();
            for (SnapshotNode root : roots(loadedPlan.tree())) {
                LocatorIndex index = StructuralLocator.defaultLocator().locate(root);
                index(root, index, addresses);
                roots.add(new RootBinding(index));
            }
            return new SnapshotBoundReferences(
                    Collections.unmodifiableList(roots), Collections.unmodifiableMap(addresses));
        }

        @Override
        public String expose(String structuralAddress, String componentClass) {
            requireAddress(structuralAddress, componentClass);
            return structuralAddress;
        }

        @Override
        public String expose(TestElement element) {
            String address = addressesByElement.get(element);
            if (address == null) {
                throw new IllegalArgumentException("Node is not part of this bound request");
            }
            return address;
        }

        private static void index(
                SnapshotNode node, LocatorIndex addresses, Map<TestElement, String> addressesByElement) {
            addressesByElement.put(node.element, addresses.locatorFor(node));
            for (SnapshotNode child : node.children) {
                index(child, addresses, addressesByElement);
            }
        }

        @Override
        public boolean matches(
                ResolvedNodeHandle handle, String structuralAddress, String componentClass) {
            if (!(handle instanceof ExactNodeHandle)) {
                return false;
            }
            SnapshotNode node = findAddress(structuralAddress, componentClass);
            return node != null && node.element == ((ExactNodeHandle) handle).element();
        }

        @Override
        public ReferenceResolution resolve(String publicReference) {
            if (publicReference == null || publicReference.trim().isEmpty()) {
                return ReferenceResolution.unavailable();
            }
            for (RootBinding root : roots) {
                try {
                    SnapshotNode node = (SnapshotNode) root.index.requireUnique(publicReference);
                    return ReferenceResolution.resolved(ExactNodeHandle.of(node.element));
                } catch (LocatorNotFoundException ignored) {
                }
            }
            return ReferenceResolution.unavailable();
        }

        private void requireAddress(String structuralAddress, String componentClass) {
            if (findAddress(structuralAddress, componentClass) == null) {
                throw new IllegalArgumentException("Address is not uniquely part of this bound request");
            }
        }

        private SnapshotNode findAddress(String structuralAddress, String componentClass) {
            if (structuralAddress == null || componentClass == null) {
                return null;
            }
            for (RootBinding root : roots) {
                try {
                    SnapshotNode node = (SnapshotNode) root.index.requireUnique(structuralAddress);
                    if (componentClass.equals(node.element.getClass().getName())) {
                        return node;
                    }
                } catch (LocatorNotFoundException ignored) {
                }
            }
            return null;
        }
    }

    private static List<SnapshotNode> roots(HashTree tree) {
        List<SnapshotNode> roots = new ArrayList<>();
        for (Object rawNode : tree.list()) {
            if (rawNode instanceof TestElement) {
                TestElement element = (TestElement) rawNode;
                roots.add(new SnapshotNode(element, roots(tree.getTree(rawNode))));
            } else {
                roots.addAll(roots(tree.getTree(rawNode)));
            }
        }
        return roots;
    }

    private static final class RootBinding {
        private final LocatorIndex index;

        private RootBinding(LocatorIndex index) {
            this.index = index;
        }
    }

    private static final class SnapshotNode implements LocatorNode {
        private final TestElement element;
        private final List<SnapshotNode> children;

        private SnapshotNode(TestElement element, List<SnapshotNode> children) {
            this.element = element;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }

        @Override
        public String componentClass() {
            String testClass = element.getPropertyAsString(TestElement.TEST_CLASS);
            if (testClass == null || testClass.trim().isEmpty()) {
                return element.getClass().getSimpleName();
            }
            int packageSeparator = testClass.lastIndexOf('.');
            return packageSeparator < 0 ? testClass : testClass.substring(packageSeparator + 1);
        }

        @Override
        public List<SnapshotNode> children() {
            return children;
        }

        @Override
        public String displayName() {
            return element.getName();
        }
    }
}
