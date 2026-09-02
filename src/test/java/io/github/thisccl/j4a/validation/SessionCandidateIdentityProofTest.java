package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.MutationOutcome;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class SessionCandidateIdentityProofTest {
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initializeRuntime() throws Exception {
        LocalJMeterWorkerRuntime.initialize(JMETER_HOME);
    }

    @Test
    void acceptsCandidateRoundTripAfterSubtreeMoveAndTrackedOwnPropertyChange() throws Exception {
        Fixture fixture = fixture("happy");
        fixture.left.setName("tracked-after-move");
        HashTree moved = fixture.rootChildren.remove(fixture.left);
        fixture.rootChildren.getTree(fixture.right).add(fixture.left, moved);
        PreparedReferenceState proposal = fixture.reconcile();
        JmxTestPlan reloaded = roundTrip(fixture.plan, "happy-candidate.jmx");

        assertThatCode(() -> SessionCandidateIdentityProof.requireProven(fixture.plan, reloaded, proposal))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDistinguishableSameClassSiblingSwap() throws Exception {
        Fixture fixture = fixture("distinct-swap");
        PreparedReferenceState proposal = fixture.reconcile();
        JmxTestPlan reloaded = fixturePlan("right", "left", "untracked");

        assertReconciliationFailure(fixture.plan, reloaded, proposal);
    }

    @Test
    void rejectsByteEquivalentTrackedSiblingPositionExchangeAtExpectedBoundary() throws Exception {
        Fixture fixture = flatFixture("equivalent-swap");
        PreparedReferenceState proposal = fixture.reconcile();
        HashTree leftTree = fixture.rootChildren.remove(fixture.left);
        HashTree rightTree = fixture.rootChildren.remove(fixture.right);
        fixture.rootChildren.add(fixture.right, rightTree);
        fixture.rootChildren.add(fixture.left, leftTree);

        assertReconciliationFailure(fixture.plan, fixture.plan, proposal);
    }

    @Test
    void rejectsWholeTreeTopologyDriftBeforeCommit() throws Exception {
        Fixture fixture = fixture("topology");
        PreparedReferenceState proposal = fixture.reconcile();
        JmxTestPlan reloaded = fixturePlan("left", "right", "untracked");
        reloaded.tree().getTree(reloaded.depthFirstTestElements().get(0)).add(controller("inserted"));

        assertReconciliationFailure(fixture.plan, reloaded, proposal);
    }

    @Test
    void ignoresUntrackedDescendantOwnValueChanges() throws Exception {
        Fixture fixture = fixture("untracked-value");
        PreparedReferenceState proposal = fixture.reconcile();
        JmxTestPlan reloaded = roundTrip(fixture.plan, "untracked-candidate.jmx");
        TestElement untracked = reloaded.depthFirstTestElements().get(2);
        untracked.setName("changed-only-after-reload");

        assertThatCode(() -> SessionCandidateIdentityProof.requireProven(fixture.plan, reloaded, proposal))
                .doesNotThrowAnyException();
    }

    @Test
    void sessionApplyRejectsTopologyDriftRegistryNeutrallyBeforeCommit() throws Exception {
        Path source = tempDir.resolve("session-source.jmx");
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        Path target = tempDir.resolve("session-target.jmx");
        byte[] sentinel = "uncommitted-target".getBytes(StandardCharsets.UTF_8);
        Files.write(target, sentinel);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 100);
        LocalJMeterWorkerOperations operations = LocalJMeterWorkerOperations.session(
                registry, new LocalJMeterWorkerOrderCapability(() -> { }));
        String rendered = operations.execute(LocalJMeterWorkerRequest.renderReadData(
                source, JMETER_HOME, "5", null, "NONE", "false"));
        String reference = firstReference(rendered);
        String patch = "changes:\n  - set:\n      ref: " + reference + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: candidate-name\n          type: string\n";

        Files.write(source, "external-change".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, JMETER_HOME, patch, target, true),
                registry))
                .isInstanceOfSatisfying(ReferenceFailure.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("MCP_REF_NOT_FOUND"));
        org.assertj.core.api.Assertions.assertThat(Files.readAllBytes(target)).containsExactly(sentinel);
        assertThatThrownBy(() -> operations.execute(LocalJMeterWorkerRequest.renderReadData(
                source, JMETER_HOME, "5", reference, "NONE", "false")))
                .isInstanceOf(ReferenceFailure.class);
    }

    private Fixture fixture(String name) throws Exception {
        return fixture(name, fixturePlan("left", "right", "untracked"), 3);
    }

    private Fixture flatFixture(String name) throws Exception {
        return fixture(name, flatPlan("same", "same"), 2);
    }

    private Fixture fixture(String name, JmxTestPlan plan, int rightIndex) throws Exception {
        TestElement root = plan.depthFirstTestElements().get(0);
        TestElement left = plan.depthFirstTestElements().get(1);
        TestElement right = plan.depthFirstTestElements().get(rightIndex);
        Path source = tempDir.resolve(name + ".jmx");
        Files.write(source, name.getBytes(StandardCharsets.UTF_8));
        Path home = Files.createDirectory(tempDir.resolve(name + "-home"));
        SourceSnapshot<JmxTestPlan> snapshot = SourceSnapshot.read(source, ignored -> plan);
        DocumentIdentity identity = DocumentIdentity.of(home, source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 20);
        SessionReferenceRegistry.PreparedState exposure = registry.prepare(identity);
        SessionReferenceSpace exposureSpace = new SessionReferenceSpace(exposure, identity, snapshot);
        BoundReferences exposed = exposureSpace.bind(plan);
        io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(exposed, left);
        io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(exposed, right);
        exposureSpace.prepareSuccessfulUse();
        registry.publish(exposure);
        SessionReferenceRegistry.PreparedState mutation = registry.prepare(identity);
        BoundReferences references = new SessionReferenceSpace(mutation, identity, snapshot).bind(plan);
        SessionReferenceSpace.requireRetainedRecords(references);
        HashTree rootChildren = plan.tree().getTree(root);
        return new Fixture(plan, left, right, rootChildren, references);
    }

    private JmxTestPlan roundTrip(JmxTestPlan plan, String name) throws Exception {
        Path candidate = tempDir.resolve(name);
        LocalJMeterWorkerJmx.save(plan, candidate);
        return LocalJMeterWorkerJmx.load(candidate, JMETER_HOME);
    }

    private static JmxTestPlan fixturePlan(String leftName, String rightName, String untrackedName) {
        TestPlan root = new TestPlan("root");
        root.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
        root.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        ListedHashTree tree = new ListedHashTree();
        HashTree children = tree.add(root);
        GenericController left = controller(leftName);
        children.add(left).add(controller(untrackedName));
        children.add(controller(rightName));
        return new JmxTestPlan(tree);
    }

    private static JmxTestPlan flatPlan(String leftName, String rightName) {
        TestPlan root = new TestPlan("root");
        root.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
        root.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        ListedHashTree tree = new ListedHashTree();
        HashTree children = tree.add(root);
        children.add(controller(leftName));
        children.add(controller(rightName));
        return new JmxTestPlan(tree);
    }

    private static GenericController controller(String name) {
        GenericController controller = new GenericController();
        controller.setName(name);
        controller.setProperty(TestElement.TEST_CLASS, GenericController.class.getName());
        controller.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.LogicControllerGui");
        return controller;
    }

    private static String firstReference(String yaml) {
        List<String> references = new ArrayList<String>();
        collectReferences(new Yaml().load(yaml), references);
        return references.get(0);
    }

    private static void collectReferences(Object value, List<String> references) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("ref".equals(entry.getKey()) && entry.getValue() instanceof String) {
                    references.add((String) entry.getValue());
                }
                collectReferences(entry.getValue(), references);
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectReferences(item, references);
            }
        }
    }

    private static void assertReconciliationFailure(
            JmxTestPlan expected, JmxTestPlan actual, PreparedReferenceState proposal) {
        assertThatThrownBy(() -> SessionCandidateIdentityProof.requireProven(expected, actual, proposal))
                .isInstanceOfSatisfying(ReferenceFailure.class, failure -> {
                    org.assertj.core.api.Assertions.assertThat(failure.reason())
                            .isEqualTo(ReferenceFailure.Reason.RECONCILIATION_FAILED);
                    org.assertj.core.api.Assertions.assertThat(failure.code())
                            .isEqualTo("MCP_REF_RECONCILIATION_FAILED");
                    org.assertj.core.api.Assertions.assertThat(failure.category()).isEqualTo("runtime");
                    org.assertj.core.api.Assertions.assertThat(failure.suggestedAction())
                            .contains("inspect or simplify").doesNotContain("read");
                });
    }

    private static final class Fixture {
        private final JmxTestPlan plan;
        private final TestElement left;
        private final TestElement right;
        private final HashTree rootChildren;
        private final BoundReferences references;

        private Fixture(
                JmxTestPlan plan,
                TestElement left,
                TestElement right,
                HashTree rootChildren,
                BoundReferences references) {
            this.plan = plan;
            this.left = left;
            this.right = right;
            this.rootChildren = rootChildren;
            this.references = references;
        }

        private PreparedReferenceState reconcile() {
            return SessionReferenceSpace.reconcile(references, plan, MutationOutcome.applied(1));
        }
    }
}
