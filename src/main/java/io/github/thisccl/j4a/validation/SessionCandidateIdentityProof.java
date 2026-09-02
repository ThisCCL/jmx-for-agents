package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

final class SessionCandidateIdentityProof {
    private SessionCandidateIdentityProof() {
    }

    static void requireProven(
            JmxTestPlan expectedCandidate,
            JmxTestPlan reloadedCandidate,
            PreparedReferenceState proposedState) throws ReferenceFailure {
        Objects.requireNonNull(expectedCandidate, "expectedCandidate");
        Objects.requireNonNull(reloadedCandidate, "reloadedCandidate");
        Objects.requireNonNull(proposedState, "proposedState");
        try {
            List<TopologyNode> expectedTopology = topology(expectedCandidate.tree());
            List<TopologyNode> actualTopology = topology(reloadedCandidate.tree());
            if (!expectedTopology.equals(actualTopology)) {
                throw failure("complete ordered component topology changed after SaveService reload");
            }
            SessionPlanIndex expectedIndex = SessionPlanIndex.create(expectedCandidate);
            SessionPlanIndex actualIndex = SessionPlanIndex.create(reloadedCandidate);
            for (PreparedReferenceState.TrackedReference survivor : proposedState.survivingReferences()) {
                requireClaim(expectedIndex, actualIndex, new IdentityClaim(survivor));
            }
            for (PreparedReferenceState.CreatedAlias alias : proposedState.createdAliases()) {
                requireClaim(expectedIndex, actualIndex, new IdentityClaim(alias));
            }
        } catch (ReferenceFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw ReferenceFailure.reconciliationFailed(exception);
        } catch (RuntimeException exception) {
            throw ReferenceFailure.reconciliationFailed(exception);
        }
    }

    private static void requireClaim(
            SessionPlanIndex expectedIndex,
            SessionPlanIndex actualIndex,
            IdentityClaim claim) throws IOException, ReferenceFailure {
        SessionPlanIndex.LocatedElement expected = expectedIndex.find(claim.locator);
        if (expected == null || expected.element() != claim.expectedIdentity) {
            throw failure(claim.label + " no longer occupies its proposed ordered position");
        }
        if (!claim.expectedClass.equals(expected.element().getClass().getName())) {
            throw failure(claim.label + " has an unexpected in-memory component class");
        }
        SessionPlanIndex.LocatedElement actual = actualIndex.find(claim.locator);
        if (actual == null || !claim.expectedClass.equals(actual.element().getClass().getName())) {
            throw failure(claim.label + " is missing or changed class after SaveService reload");
        }
        if (!Arrays.equals(ownPersistedState(expected.element()), ownPersistedState(actual.element()))) {
            throw failure(claim.label + " changed its own persisted state after SaveService reload");
        }
    }

    private static byte[] ownPersistedState(TestElement element) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(element, output);
        return output.toByteArray();
    }

    private static List<TopologyNode> topology(HashTree tree) {
        List<TopologyNode> nodes = new ArrayList<TopologyNode>();
        collectTopology(logicalChildren(tree), Collections.<Integer>emptyList(), nodes);
        return Collections.unmodifiableList(nodes);
    }

    private static void collectTopology(
            List<LogicalNode> siblings, List<Integer> parentAddress, List<TopologyNode> nodes) {
        for (int index = 0; index < siblings.size(); index++) {
            LogicalNode node = siblings.get(index);
            List<Integer> address = new ArrayList<Integer>(parentAddress);
            address.add(index);
            nodes.add(new TopologyNode(address, node.element.getClass().getName()));
            collectTopology(node.children, address, nodes);
        }
    }

    private static List<LogicalNode> logicalChildren(HashTree tree) {
        List<LogicalNode> nodes = new ArrayList<LogicalNode>();
        for (Object key : tree.list()) {
            HashTree subtree = tree.getTree(key);
            if (key instanceof TestElement) {
                nodes.add(new LogicalNode((TestElement) key, logicalChildren(subtree)));
            } else {
                nodes.addAll(logicalChildren(subtree));
            }
        }
        return nodes;
    }

    private static ReferenceFailure failure(String detail) {
        return ReferenceFailure.reconciliationFailed(new IllegalStateException(detail));
    }

    private static final class LogicalNode {
        private final TestElement element;
        private final List<LogicalNode> children;

        private LogicalNode(TestElement element, List<LogicalNode> children) {
            this.element = element;
            this.children = children;
        }
    }

    private static final class IdentityClaim {
        private final String locator;
        private final String expectedClass;
        private final TestElement expectedIdentity;
        private final String label;

        private IdentityClaim(PreparedReferenceState.TrackedReference survivor) {
            this.locator = survivor.locator();
            this.expectedClass = survivor.expectedClass();
            this.expectedIdentity = ExactNodeHandles.element(survivor.handle());
            this.label = "tracked ref " + survivor.publicReference();
        }

        private IdentityClaim(PreparedReferenceState.CreatedAlias alias) {
            this.locator = alias.locator();
            this.expectedClass = alias.expectedClass();
            this.expectedIdentity = ExactNodeHandles.element(alias.handle());
            this.label = "created alias " + alias.alias();
        }
    }

    private static final class TopologyNode {
        private final List<Integer> address;
        private final String componentClass;

        private TopologyNode(List<Integer> address, String componentClass) {
            this.address = Collections.unmodifiableList(new ArrayList<Integer>(address));
            this.componentClass = componentClass;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TopologyNode)) {
                return false;
            }
            TopologyNode that = (TopologyNode) other;
            return address.equals(that.address) && componentClass.equals(that.componentClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, componentClass);
        }
    }
}
