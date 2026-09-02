package io.github.thisccl.j4a.reference;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.validation.ReferenceTestSupport;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class ComponentReferenceSpaceContractTest {
    @Test
    void immutableAddressMatcherAcceptsOnlyTheExactAuthenticatedHandle() {
        Fixture fixture = fixture("left-sibling", "right-sibling");
        BoundReferences bound = ReferenceTestSupport.snapshot(fixture.plan);
        String leftRef = ReferenceTestSupport.expose(bound, fixture.left);
        ResolvedNodeHandle leftHandle = bound.resolve(leftRef).handle().get();
        ResolvedNodeHandle fabricated = new ResolvedNodeHandle() { };

        assertThat(bound.matches(leftHandle, leftRef, fixture.left.getClass().getName())).isTrue();
        assertThat(bound.matches(fabricated, leftRef, fixture.left.getClass().getName())).isFalse();
        assertThat(bound.matches(leftHandle, "not-an-address", fixture.left.getClass().getName())).isFalse();
    }

    @Test
    void snapshotSpaceResolvesDistinguishableSameClassSiblingsToExactOpaqueHandles() {
        Fixture fixture = fixture("left-sibling", "right-sibling");
        BoundReferences bound = ReferenceTestSupport.snapshot(fixture.plan);

        String leftRef = ReferenceTestSupport.expose(bound, fixture.left);
        String rightRef = ReferenceTestSupport.expose(bound, fixture.right);

        assertThat(leftRef).isNotEqualTo(rightRef);
        assertThat(bound.resolve(leftRef).status()).isEqualTo(ReferenceResolution.Status.RESOLVED);
        assertThat(ReferenceTestSupport.element(bound.resolve(leftRef).handle().get())).isSameAs(fixture.left);
        assertThat(ReferenceTestSupport.element(bound.resolve(rightRef).handle().get())).isSameAs(fixture.right);
    }

    @Test
    void snapshotSpaceExposesLegacyStructuralRefsWithoutRetainingRequestState() {
        Fixture fixture = fixture("left-sibling", "right-sibling");
        BoundReferences bound = ReferenceTestSupport.snapshot(fixture.plan);

        assertThat(ReferenceTestSupport.expose(bound, fixture.root)).isEqualTo("jmx_fab2ad179d11");
        assertThat(ReferenceTestSupport.expose(bound, fixture.left)).isEqualTo("jmx_6b84262a7702");
        assertThat(ReferenceTestSupport.expose(bound, fixture.right)).isEqualTo("jmx_089de75a7a98");
        assertUnavailable(bound.resolve(null));
        assertUnavailable(bound.resolve("   "));
        assertUnavailable(bound.resolve("not-a-structural-ref"));

        Fixture nextFixture = fixture("renamed-left", "renamed-right");
        BoundReferences nextBound = ReferenceTestSupport.snapshot(nextFixture.plan);
        assertThat(ReferenceTestSupport.expose(nextBound, nextFixture.left)).isEqualTo("jmx_6b84262a7702");
        assertThat(ReferenceTestSupport.element(nextBound.resolve("jmx_6b84262a7702").handle().get()))
                .isSameAs(nextFixture.left);
    }

    private static void assertUnavailable(ReferenceResolution resolution) {
        assertThat(resolution.status()).isEqualTo(ReferenceResolution.Status.UNAVAILABLE);
        assertThat(resolution.handle()).isEmpty();
    }

    private static Fixture fixture(String leftName, String rightName) {
        ProbeElement root = new ProbeElement("root");
        ProbeElement left = new ProbeElement(leftName);
        ProbeElement right = new ProbeElement(rightName);
        ListedHashTree tree = new ListedHashTree();
        HashTree children = tree.add(root);
        children.add(left);
        children.add(right);
        return new Fixture(new JmxTestPlan(tree), root, left, right);
    }

    private static final class Fixture {
        private final JmxTestPlan plan;
        private final ProbeElement root;
        private final ProbeElement left;
        private final ProbeElement right;

        private Fixture(JmxTestPlan plan, ProbeElement root, ProbeElement left, ProbeElement right) {
            this.plan = plan;
            this.root = root;
            this.left = left;
            this.right = right;
        }
    }

    private static final class ProbeElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;

        private ProbeElement(String name) {
            setName(name);
        }
    }
}
