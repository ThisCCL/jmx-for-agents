package io.github.thisccl.j4a.read;

import io.github.thisccl.j4a.jmx.JmxSourceLineIndex;
import io.github.thisccl.j4a.jmx.JmxSourceRange;
import io.github.thisccl.j4a.locator.LocatorNode;
import io.github.thisccl.j4a.validation.RuntimeComponentIdentityResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

final class JmxComponentNode implements LocatorNode {
    private final TestElement element;
    private final List<JmxComponentNode> children;
    private final Optional<JmxSourceRange> sourceRange;
    private final JmxSourceLineIndex.SourceMatch sourceMatch;
    private final String runtimeComponentIdentity;

    private JmxComponentNode(
            TestElement element,
            List<JmxComponentNode> children,
            Optional<JmxSourceLineIndex.SourceMatch> sourceMatch,
            RuntimeComponentIdentityResolver identityResolver) {
        this.element = Objects.requireNonNull(element, "element");
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
        Optional<JmxSourceLineIndex.SourceMatch> match = Objects.requireNonNull(sourceMatch, "sourceMatch");
        this.sourceMatch = match.orElse(null);
        this.sourceRange = match.isPresent() ? Optional.of(match.get().range()) : Optional.empty();
        this.runtimeComponentIdentity = identityResolver.resolve(element);
    }

    static List<JmxComponentNode> fromTree(HashTree tree) {
        return fromTree(tree, JmxSourceLineIndex.empty(),
                RuntimeComponentIdentityResolver.actualClasses());
    }

    static List<JmxComponentNode> fromTree(HashTree tree, JmxSourceLineIndex sourceLineIndex) {
        return fromTree(tree, sourceLineIndex,
                RuntimeComponentIdentityResolver.actualClasses());
    }

    static List<JmxComponentNode> fromTree(
            HashTree tree,
            JmxSourceLineIndex sourceLineIndex,
            RuntimeComponentIdentityResolver identityResolver) {
        return fromTree(tree, sourceLineIndex.cursor(), identityResolver);
    }

    private static List<JmxComponentNode> fromTree(
            HashTree tree,
            JmxSourceLineIndex.Cursor sourceCursor,
            RuntimeComponentIdentityResolver identityResolver) {
        List<JmxComponentNode> nodes = new ArrayList<>();
        for (Object rawNode : tree.list()) {
            if (rawNode instanceof TestElement) {
                TestElement testElement = (TestElement) rawNode;
                Optional<JmxSourceLineIndex.SourceMatch> sourceMatch = sourceCursor.consumeSource(testElement);
                nodes.add(new JmxComponentNode(
                        testElement,
                        fromTree(tree.getTree(rawNode), sourceCursor, identityResolver),
                        sourceMatch,
                        identityResolver));
            }
        }
        return Collections.unmodifiableList(nodes);
    }

    TestElement element() {
        return element;
    }

    String runtimeComponentIdentity() {
        return runtimeComponentIdentity;
    }

    @Override
    public String componentClass() {
        String testClass = element.getPropertyAsString(TestElement.TEST_CLASS);
        if (testClass == null || testClass.trim().isEmpty()) {
            return element.getClass().getSimpleName();
        }
        int lastPackageSeparator = testClass.lastIndexOf('.');
        return lastPackageSeparator >= 0 ? testClass.substring(lastPackageSeparator + 1) : testClass;
    }

    @Override
    public List<JmxComponentNode> children() {
        return children;
    }

    @Override
    public String displayName() {
        return element.getName();
    }

    @Override
    public boolean enabled() {
        return element.isEnabled();
    }

    Optional<JmxSourceRange> sourceRange() {
        return sourceRange;
    }

    List<String> argumentElementTypes(String propertyName) {
        return sourceMatch == null
                ? Collections.<String>emptyList()
                : sourceMatch.argumentElementTypes(propertyName);
    }
}
