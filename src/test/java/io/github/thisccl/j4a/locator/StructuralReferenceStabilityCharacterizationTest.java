package io.github.thisccl.j4a.locator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralReferenceStabilityCharacterizationTest {
    private final StructuralLocator locator = StructuralLocator.defaultLocator();

    @Test
    void moveChangesReferenceForTheSameNodeAndRecordsResolvedIdentity() {
        MutableNode moved = node("move-me");
        MutableNode source = node("source-subtree").child(moved);
        MutableNode destination = node("destination-subtree").child(node("destination-child"));
        MutableNode root = node("plan").child(source).child(destination);

        LocatorIndex beforeIndex = locator.locate(root);
        String beforeRef = beforeIndex.locatorFor(moved);

        source.children.remove(moved);
        destination.child(moved);

        LocatorIndex afterIndex = locator.locate(root);
        String afterRef = afterIndex.locatorFor(moved);

        assertThat(beforeRef).as("move must retarget the structural ref").isNotEqualTo(afterRef);
        assertThat(afterIndex.requireUnique(afterRef)).isSameAs(moved);
        assertThat(afterIndex.requireUnique(afterRef).displayName())
                .isEqualTo("move-me");

        receipt("move", beforeRef, afterRef, beforeIndex.requireUnique(beforeRef), afterIndex.requireUnique(afterRef));
    }

    @Test
    void insertingAnAncestorChangesReferenceForTheSameNodeAndRecordsResolvedIdentity() {
        MutableNode target = node("ancestor-target");
        MutableNode originalParent = node("original-parent").child(target);
        MutableNode root = node("plan").child(originalParent);

        LocatorIndex beforeIndex = locator.locate(root);
        String beforeRef = beforeIndex.locatorFor(target);

        MutableNode insertedAncestor = node("inserted-ancestor").child(target);
        originalParent.children.clear();
        originalParent.child(insertedAncestor);

        LocatorIndex afterIndex = locator.locate(root);
        String afterRef = afterIndex.locatorFor(target);

        assertThat(beforeRef)
                .as("inserting an ancestor must retarget the structural ref")
                .isNotEqualTo(afterRef);
        assertThat(afterIndex.requireUnique(afterRef)).isSameAs(target);
        assertThat(afterIndex.requireUnique(afterRef).displayName())
                .isEqualTo("ancestor-target");

        receipt("ancestor-insertion", beforeRef, afterRef, beforeIndex.requireUnique(beforeRef), afterIndex.requireUnique(afterRef));
    }

    @Test
    void insertingSameClassPredecessorChangesReferenceForTheSameNode() {
        MutableNode target = node("predecessor-target");
        MutableNode parent = node("sibling-parent").child(node("existing-predecessor")).child(target);
        MutableNode root = node("plan").child(parent);

        LocatorIndex beforeIndex = locator.locate(root);
        String beforeRef = beforeIndex.locatorFor(target);

        parent.children.add(1, node("inserted-predecessor"));

        LocatorIndex afterIndex = locator.locate(root);
        String afterRef = afterIndex.locatorFor(target);

        assertThat(beforeRef)
                .as("inserting a same-class predecessor must retarget the structural ref")
                .isNotEqualTo(afterRef);
        assertThat(afterIndex.requireUnique(afterRef)).isSameAs(target);
        assertThat(afterIndex.requireUnique(afterRef).displayName())
                .isEqualTo("predecessor-target");

        receipt("same-class-predecessor-insertion", beforeRef, afterRef,
                beforeIndex.requireUnique(beforeRef), afterIndex.requireUnique(afterRef));
    }

    @Test
    void oldOrdinalReferenceCanResolveToTheWrongDistinguishableSibling() {
        MutableNode first = node("first-sibling");
        MutableNode target = node("target-sibling");
        MutableNode parent = node("sibling-parent").child(first).child(target);
        MutableNode root = node("plan").child(parent);

        assertThat(first.displayName())
                .as("same-class siblings must remain distinguishable for this characterization")
                .isNotEqualTo(target.displayName());

        LocatorIndex beforeIndex = locator.locate(root);
        String oldTargetRef = beforeIndex.locatorFor(target);

        MutableNode inserted = node("inserted-sibling");
        assertThat(inserted.componentClass()).isEqualTo(target.componentClass());
        parent.children.add(0, inserted);

        LocatorIndex afterIndex = locator.locate(root);
        String targetAfterRef = afterIndex.locatorFor(target);
        LocatorNode resolvedByOldRef = afterIndex.requireUnique(oldTargetRef);

        assertThat(targetAfterRef)
                .as("target ordinal changes after a same-class predecessor insertion")
                .isNotEqualTo(oldTargetRef);
        assertThat(resolvedByOldRef)
                .as("the old ordinal-derived ref must not identify the original object")
                .isNotSameAs(target);
        assertThat(resolvedByOldRef.displayName())
                .as("the old ref resolves to a distinguishable wrong sibling")
                .isEqualTo("first-sibling");

        receipt("old-ref-wrong-sibling", oldTargetRef, targetAfterRef,
                beforeIndex.requireUnique(oldTargetRef), resolvedByOldRef);
    }

    private static MutableNode node(String name) {
        return new MutableNode("HTTPSamplerProxy", name);
    }

    private static void receipt(
            String hazard,
            String beforeRef,
            String afterRef,
            LocatorNode beforeNode,
            LocatorNode afterNode) {
        System.out.println(
                "CHARACTERIZATION hazard=" + hazard
                        + " beforeRef=" + beforeRef
                        + " afterRef=" + afterRef
                        + " beforeResolved=" + beforeNode.displayName() + "/" + beforeNode.componentClass()
                        + " afterResolved=" + afterNode.displayName() + "/" + afterNode.componentClass());
    }

    private static final class MutableNode implements LocatorNode {
        private final String componentClass;
        private final String name;
        private final List<MutableNode> children = new ArrayList<>();

        private MutableNode(String componentClass, String name) {
            this.componentClass = componentClass;
            this.name = name;
        }

        private MutableNode child(MutableNode child) {
            children.add(child);
            return this;
        }

        @Override
        public String componentClass() {
            return componentClass;
        }

        @Override
        public List<? extends LocatorNode> children() {
            return children;
        }

        @Override
        public String displayName() {
            return name;
        }
    }
}
