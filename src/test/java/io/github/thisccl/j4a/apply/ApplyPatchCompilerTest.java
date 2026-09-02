package io.github.thisccl.j4a.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import io.github.thisccl.j4a.reference.ComponentReferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class ApplyPatchCompilerTest {
    private static final String COMPONENT = "org.example.Component";

    @Test
    void compilesAnOrdinaryReferenceIntoItsExactInputNodeHandle() {
        ProbeElement target = new ProbeElement("target");
        ListedHashTree tree = new ListedHashTree();
        tree.add(target);
        JmxTestPlan inputPlan = new JmxTestPlan(tree);
        BoundReferences references = io.github.thisccl.j4a.validation.ReferenceTestSupport.snapshot(inputPlan);
        String publicReference = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(references, target);
        ApplyPatch patch = new ApplyPatch(Collections.singletonList(new ApplyPatch.Change(
                new ApplyPatch.SetOperation(
                        publicReference,
                        Optional.empty(),
                        Collections.singletonList(new ApplyPatch.PropertyChange(
                                new PropertyAddress(Collections.<Object>singletonList("name")),
                                "updated",
                                ApplyPatch.ValueType.STRING))))));

        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(patch, references);

        assertThat(resolved.changes()).hasSize(1);
        ResolvedApplyPlan.SetOperation operation =
                (ResolvedApplyPlan.SetOperation) resolved.changes().get(0).operation();
        assertThat(operation.ref()).isInstanceOf(ResolvedApplyPlan.InputNodeReference.class);
        ResolvedApplyPlan.InputNodeReference input =
                (ResolvedApplyPlan.InputNodeReference) operation.ref();
        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.element(input.handle())).isSameAs(target);
    }

    @Test
    void carriesTheImmutableUnresolvedAddressThroughCompilationWithoutGraphResolution() {
        Fixture fixture = fixture(1);
        PropertyAddress address = new PropertyAddress(Arrays.<Object>asList(
                "HeaderManager.headers", Integer.valueOf(0), "Header.name"));
        ApplyPatch.PropertyChange change = new ApplyPatch.PropertyChange(
                address, "X-Trace", ApplyPatch.ValueType.STRING);
        ApplyPatch patch = patch(new ApplyPatch.SetOperation(
                fixture.ref(fixture.children.get(0)),
                Optional.empty(),
                Collections.singletonList(change)));

        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(patch, fixture.references);

        ApplyPatch.PropertyChange compiled = operation(
                resolved, 0, ResolvedApplyPlan.SetOperation.class).properties().get(0);
        assertThat(compiled.property()).isSameAs(address);
        assertThatThrownBy(() -> compiled.property().segments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exposesOnlyPropertyAddressPropertyChangeConstructor() {
        List<Class<?>> propertyParameterTypes = new ArrayList<Class<?>>();
        for (java.lang.reflect.Constructor<?> constructor
                : ApplyPatch.PropertyChange.class.getConstructors()) {
            propertyParameterTypes.add(constructor.getParameterTypes()[0]);
        }

        assertThat(propertyParameterTypes).containsExactly(PropertyAddress.class);
    }

    @Test
    void compilesEveryReferenceFieldAndKeepsSymbolsInDeclarationOrder() {
        Fixture fixture = fixture(5);
        RecordingBoundReferences references = new RecordingBoundReferences(fixture.references);
        String root = fixture.ref(fixture.root);
        String first = fixture.ref(fixture.children.get(0));
        String second = fixture.ref(fixture.children.get(1));
        String third = fixture.ref(fixture.children.get(2));
        String fourth = fixture.ref(fixture.children.get(3));
        ApplyPatch patch = patch(
                new ApplyPatch.AddOperation(
                        ordinary(root), Optional.of(ordinary(first)), Optional.empty(), Optional.empty(),
                        COMPONENT, Collections.emptyList(), Optional.of("alpha")),
                new ApplyPatch.AddOperation(
                        alias("alpha"), Optional.empty(), Optional.of(ordinary(second)), Optional.empty(),
                        COMPONENT, Collections.emptyList(), Optional.of("beta")),
                new ApplyPatch.SetOperation(ordinary(fourth), Optional.empty(), propertyChanges()),
                new ApplyPatch.MoveOperation(
                        ordinary(third), Optional.empty(), ordinary(root), Optional.of(ordinary(first)),
                        Optional.empty(), Optional.empty()),
                new ApplyPatch.MoveOperation(
                        alias("beta"), Optional.empty(), ordinary(root), Optional.empty(),
                        Optional.of(ordinary(second)), Optional.empty()),
                new ApplyPatch.SetOperation(alias("beta"), Optional.empty(), propertyChanges()),
                new ApplyPatch.DeleteOperation(ordinary(fourth), Optional.empty()));

        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(patch, references);

        assertThat(references.resolvedRefs)
                .containsExactly(root, first, second, fourth, third, root, first, root, second, fourth);
        assertThat(resolved.symbolSlots()).extracting(ResolvedApplyPlan.SymbolSlot::alias)
                .containsExactly("alpha", "beta");
        assertThat(resolved.symbolSlots()).extracting(ResolvedApplyPlan.SymbolSlot::declarationOrder)
                .containsExactly(0, 1);
        ResolvedApplyPlan.SymbolSlot alpha = resolved.symbolSlots().get(0);
        ResolvedApplyPlan.SymbolSlot beta = resolved.symbolSlots().get(1);
        ResolvedApplyPlan.AddOperation firstAdd = operation(resolved, 0, ResolvedApplyPlan.AddOperation.class);
        ResolvedApplyPlan.AddOperation secondAdd = operation(resolved, 1, ResolvedApplyPlan.AddOperation.class);
        assertThat(firstAdd.declaredSymbol()).containsSame(alpha);
        assertThat(secondAdd.declaredSymbol()).containsSame(beta);
        assertSymbol(secondAdd.parent(), alpha);
        assertSymbol(operation(resolved, 4, ResolvedApplyPlan.MoveOperation.class).ref(), beta);
        assertSymbol(operation(resolved, 5, ResolvedApplyPlan.SetOperation.class).ref(), beta);
    }

    @Test
    void compilesIncrementalCardsInDeclarationOrderWithUnresolvedAddressesAndPriorAliases() {
        Fixture fixture = fixture(1);
        String root = fixture.ref(fixture.root);
        PropertyAddress address = new PropertyAddress(
                Collections.<Object>singletonList("HeaderManager.headers"));
        Map<String, Object> row = Collections.<String, Object>singletonMap("Header.name", "X-Trace");
        ApplyPatch patch = patch(
                new ApplyPatch.AddOperation(
                        ordinary(root), Optional.empty(), Optional.empty(), Optional.of("last"),
                        COMPONENT, Collections.emptyList(), Optional.of("headers")),
                new ApplyPatch.AppendOperation(alias("headers"), address, row),
                new ApplyPatch.InsertOperation(alias("headers"), address, 0, row),
                new ApplyPatch.RemoveOperation(alias("headers"), address, 1));

        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(patch, fixture.references);

        assertThat(resolved.changes()).extracting(change -> change.operation().name())
                .containsExactly("add", "append", "insert", "remove");
        ResolvedApplyPlan.SymbolSlot symbol = resolved.symbolSlots().get(0);
        ResolvedApplyPlan.AppendOperation append =
                operation(resolved, 1, ResolvedApplyPlan.AppendOperation.class);
        assertSymbol(append.ref(), symbol);
        assertThat(append.property()).isSameAs(address);
        assertThatThrownBy(() -> append.property().segments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertSymbol(operation(resolved, 2, ResolvedApplyPlan.InsertOperation.class).ref(), symbol);
        assertSymbol(operation(resolved, 3, ResolvedApplyPlan.RemoveOperation.class).ref(), symbol);
    }

    @Test
    void parsesAndCompilesThePublicIncrementalCardSequences() {
        Fixture fixture = fixture(1);
        String root = fixture.ref(fixture.root);
        String target = fixture.ref(fixture.children.get(0));
        ApplyPatch patch = new ApplyPatchParser().parse("changes:\n"
                + "  - add:\n"
                + "      parent: " + root + "\n"
                + "      component: " + COMPONENT + "\n"
                + "      as: created\n"
                + "  - append:\n"
                + "      ref: $created\n"
                + "      property: [HeaderManager.headers]\n"
                + "      row: {Header.name: created}\n"
                + "  - append:\n"
                + "      ref: " + target + "\n"
                + "      property: [HeaderManager.headers]\n"
                + "      row: {Header.name: appended}\n"
                + "  - insert:\n"
                + "      ref: " + target + "\n"
                + "      property: [HeaderManager.headers]\n"
                + "      index: 0\n"
                + "      row: {Header.name: inserted}\n"
                + "  - remove:\n"
                + "      ref: " + target + "\n"
                + "      property: [HeaderManager.headers]\n"
                + "      index: 1\n");

        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(patch, fixture.references);

        assertThat(resolved.changes()).extracting(change -> change.operation().name())
                .containsExactly("add", "append", "append", "insert", "remove");
        assertSymbol(operation(resolved, 1, ResolvedApplyPlan.AppendOperation.class).ref(),
                resolved.symbolSlots().get(0));
        assertInputNode(operation(resolved, 2, ResolvedApplyPlan.AppendOperation.class).ref(),
                fixture.children.get(0));
        assertInputNode(operation(resolved, 3, ResolvedApplyPlan.InsertOperation.class).ref(),
                fixture.children.get(0));
        assertInputNode(operation(resolved, 4, ResolvedApplyPlan.RemoveOperation.class).ref(),
                fixture.children.get(0));
    }

    @Test
    void distinguishesDuplicateForwardAndUnknownAliasFailuresWithFieldContext() {
        Fixture fixture = fixture(1);
        String root = fixture.ref(fixture.root);

        ApplyPatchCompiler.CompilationException duplicate = catchThrowableOfType(
                () -> new ApplyPatchCompiler().compile(patch(
                        addAt(root, "same"),
                        addAt(root, "same")), fixture.references),
                ApplyPatchCompiler.CompilationException.class);
        assertFailure(duplicate, ApplyPatchCompiler.Reason.DUPLICATE_ALIAS, 1, "add", "as", "same");

        ApplyPatchCompiler.CompilationException forward = catchThrowableOfType(
                () -> new ApplyPatchCompiler().compile(patch(
                        new ApplyPatch.SetOperation(alias("later"), Optional.empty(), propertyChanges()),
                        addAt(root, "later")), fixture.references),
                ApplyPatchCompiler.CompilationException.class);
        assertFailure(forward, ApplyPatchCompiler.Reason.FORWARD_ALIAS, 0, "set", "ref", "$later");

        ApplyPatchCompiler.CompilationException unknown = catchThrowableOfType(
                () -> new ApplyPatchCompiler().compile(patch(
                        new ApplyPatch.DeleteOperation(alias("missing"), Optional.empty())), fixture.references),
                ApplyPatchCompiler.CompilationException.class);
        assertFailure(unknown, ApplyPatchCompiler.Reason.UNKNOWN_ALIAS, 0, "delete", "ref", "$missing");

        PropertyAddress address = new PropertyAddress(
                Collections.<Object>singletonList("HeaderManager.headers"));
        ApplyPatchCompiler.CompilationException rowForward = catchThrowableOfType(
                () -> new ApplyPatchCompiler().compile(patch(
                        new ApplyPatch.AppendOperation(
                                alias("later"), address,
                                Collections.<String, Object>singletonMap("Header.name", "value")),
                        addAt(root, "later")), fixture.references),
                ApplyPatchCompiler.CompilationException.class);
        assertFailure(rowForward, ApplyPatchCompiler.Reason.FORWARD_ALIAS,
                0, "append", "ref", "$later");
    }

    @Test
    void preservesInputObjectBindingWhenAChangeWouldShiftALaterLocator() {
        Fixture fixture = fixture(2);
        ProbeElement first = fixture.children.get(0);
        ProbeElement later = fixture.children.get(1);
        String rootRef = fixture.ref(fixture.root);
        String firstRef = fixture.ref(first);
        String laterRef = fixture.ref(later);
        ApplyPatch patch = patch(
                new ApplyPatch.AddOperation(
                        ordinary(rootRef), Optional.of(ordinary(firstRef)), Optional.empty(), Optional.empty(),
                        COMPONENT, Collections.emptyList(), Optional.of("inserted")),
                new ApplyPatch.DeleteOperation(ordinary(laterRef), Optional.empty()));

        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(patch, fixture.references);

        ProbeElement inserted = new ProbeElement("inserted-before-existing-siblings");
        fixture.prepend(inserted);
        ReferenceResolution shifted = io.github.thisccl.j4a.validation.ReferenceTestSupport.snapshot(fixture.plan).resolve(laterRef);
        assertThat(shifted.status()).isEqualTo(ReferenceResolution.Status.RESOLVED);
        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.element(
                shifted.handle().get())).isNotSameAs(later);
        ResolvedApplyPlan.DeleteOperation compiledDelete =
                operation(resolved, 1, ResolvedApplyPlan.DeleteOperation.class);
        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.element(
                ((ResolvedApplyPlan.InputNodeReference) compiledDelete.ref()).handle()))
                .isSameAs(later);
    }

    @Test
    void compilesBoundReferencesToTheExactLoadedInputObjects() {
        Fixture fixture = fixture(2);
        String firstToken = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(
                fixture.references, fixture.children.get(0));
        String secondToken = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(
                fixture.references, fixture.children.get(1));
        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(patch(
                new ApplyPatch.SetOperation(firstToken, Optional.empty(), propertyChanges()),
                new ApplyPatch.DeleteOperation(secondToken, Optional.empty())), fixture.references);

        assertInputNode(operation(resolved, 0, ResolvedApplyPlan.SetOperation.class).ref(),
                fixture.children.get(0));
        assertInputNode(operation(resolved, 1, ResolvedApplyPlan.DeleteOperation.class).ref(),
                fixture.children.get(1));
    }

    @Test
    void failsAtTheEarliestFieldAndRepeatedFailureLeavesInputUntouched() {
        Fixture fixture = fixture(1);
        String valid = io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(fixture.references, fixture.root);
        RecordingBoundReferences recording = new RecordingBoundReferences(fixture.references);
        ApplyPatch invalid = patch(
                new ApplyPatch.SetOperation(valid, Optional.empty(), propertyChanges()),
                new ApplyPatch.SetOperation("unavailable-token", Optional.empty(), propertyChanges()),
                new ApplyPatch.DeleteOperation(alias("later"), Optional.empty()),
                addAt(valid, "later"));
        List<Object> inputOrder = new ArrayList<Object>(fixture.tree.list());
        int instancesBefore = ProbeElement.instanceCount;

        for (int attempt = 0; attempt < 2; attempt++) {
            ApplyPatchCompiler.CompilationException failure = catchThrowableOfType(
                    () -> new ApplyPatchCompiler().compile(invalid, recording),
                    ApplyPatchCompiler.CompilationException.class);
            assertFailure(failure, ApplyPatchCompiler.Reason.REFERENCE_UNAVAILABLE,
                    1, "set", "ref", "unavailable-token");
        }

        assertThat(recording.resolvedRefs)
                .containsExactly(valid, "unavailable-token", valid, "unavailable-token");
        assertThat(fixture.tree.list()).containsExactlyElementsOf(inputOrder);
        assertThat(ProbeElement.instanceCount).isEqualTo(instancesBefore);
        assertThat(fixture.references.resolve(valid).status()).isEqualTo(ReferenceResolution.Status.RESOLVED);
    }

    @Test
    void rejectsUntypedReferenceVariantsWithoutCallingTheReferenceSpace() {
        Fixture fixture = fixture(1);
        RecordingBoundReferences references = new RecordingBoundReferences(fixture.references);
        ApplyPatch.ReferenceExpression misleading = new ApplyPatch.ReferenceExpression() {
            @Override
            public String spelling() {
                return fixture.ref(fixture.root);
            }
        };
        ApplyPatch invalid = patch(new ApplyPatch.SetOperation(
                misleading, Optional.empty(), propertyChanges()));

        ApplyPatchCompiler.CompilationException failure = catchThrowableOfType(
                () -> new ApplyPatchCompiler().compile(invalid, references),
                ApplyPatchCompiler.CompilationException.class);

        assertFailure(failure, ApplyPatchCompiler.Reason.UNSUPPORTED_REFERENCE_EXPRESSION,
                0, "set", "ref", fixture.ref(fixture.root));
        assertThat(references.resolvedRefs).isEmpty();
    }

    @Test
    void returnsStructurallyImmutablePlanCollectionsAndIdentityBasedSlots() {
        Fixture fixture = fixture(1);
        ResolvedApplyPlan resolved = new ApplyPatchCompiler().compile(
                patch(addAt(fixture.ref(fixture.root), "created")), fixture.references);
        ResolvedApplyPlan.AddOperation add = operation(resolved, 0, ResolvedApplyPlan.AddOperation.class);

        assertThatThrownBy(() -> resolved.changes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resolved.symbolSlots().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> add.properties().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(add.declaredSymbol().get()).isSameAs(resolved.symbolSlots().get(0));
        assertThat(Arrays.stream(ResolvedApplyPlan.SymbolSlot.class.getDeclaredMethods()))
                .noneMatch(method -> method.getName().startsWith("set") || method.getName().startsWith("bind"));
        assertThat(ResolvedApplyPlan.InputNodeReference.class.getDeclaredFields()).hasSize(1);
        assertThat(ResolvedApplyPlan.InputNodeReference.class.getDeclaredFields()[0].getType())
                .isEqualTo(io.github.thisccl.j4a.reference.ResolvedNodeHandle.class);
    }

    private static void assertFailure(
            ApplyPatchCompiler.CompilationException failure,
            ApplyPatchCompiler.Reason reason,
            int changeIndex,
            String operation,
            String field,
            String expression) {
        assertThat(failure).isNotNull();
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.changeIndex()).isEqualTo(changeIndex);
        assertThat(failure.operation()).isEqualTo(operation);
        assertThat(failure.field()).isEqualTo(field);
        assertThat(failure.expression()).isEqualTo(expression);
        assertThat(failure.getMessage())
                .startsWith("changes[" + changeIndex + "]." + operation + "." + field + ":");
    }

    private static void assertInputNode(ResolvedApplyPlan.NodeReference reference, ProbeElement expected) {
        assertThat(reference).isInstanceOf(ResolvedApplyPlan.InputNodeReference.class);
        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.element(
                ((ResolvedApplyPlan.InputNodeReference) reference).handle())).isSameAs(expected);
    }

    private static void assertSymbol(
            ResolvedApplyPlan.NodeReference reference, ResolvedApplyPlan.SymbolSlot expected) {
        assertThat(reference).isInstanceOf(ResolvedApplyPlan.SymbolReference.class);
        assertThat(((ResolvedApplyPlan.SymbolReference) reference).symbol()).isSameAs(expected);
    }

    private static <T extends ResolvedApplyPlan.Operation> T operation(
            ResolvedApplyPlan plan, int index, Class<T> operationType) {
        assertThat(plan.changes().get(index).changeIndex()).isEqualTo(index);
        return operationType.cast(plan.changes().get(index).operation());
    }

    private static ApplyPatch patch(ApplyPatch.Operation... operations) {
        List<ApplyPatch.Change> changes = new ArrayList<ApplyPatch.Change>();
        for (ApplyPatch.Operation operation : operations) {
            changes.add(new ApplyPatch.Change(operation));
        }
        return new ApplyPatch(changes);
    }

    private static ApplyPatch.AddOperation addAt(String parent, String alias) {
        return new ApplyPatch.AddOperation(
                parent,
                Optional.empty(),
                Optional.empty(),
                Optional.of("last"),
                COMPONENT,
                Collections.emptyList(),
                Optional.of(alias));
    }

    private static ApplyPatch.OrdinaryReference ordinary(String spelling) {
        return new ApplyPatch.OrdinaryReference(spelling);
    }

    private static ApplyPatch.AliasReference alias(String alias) {
        return new ApplyPatch.AliasReference(alias);
    }

    private static List<ApplyPatch.PropertyChange> propertyChanges() {
        return Collections.singletonList(
                new ApplyPatch.PropertyChange(
                        new PropertyAddress(Collections.<Object>singletonList("name")),
                        "updated",
                        ApplyPatch.ValueType.STRING));
    }

    private static Fixture fixture(int childCount) {
        ProbeElement root = new ProbeElement("root");
        List<ProbeElement> children = new ArrayList<ProbeElement>();
        ListedHashTree tree = new ListedHashTree();
        HashTree childTree = tree.add(root);
        for (int index = 0; index < childCount; index++) {
            ProbeElement child = new ProbeElement("child-" + index);
            children.add(child);
            childTree.add(child);
        }
        JmxTestPlan plan = new JmxTestPlan(tree);
        return new Fixture(tree, childTree, plan, root, children, io.github.thisccl.j4a.validation.ReferenceTestSupport.snapshot(plan));
    }

    private static final class RecordingBoundReferences implements BoundReferences {
        private final BoundReferences delegate;
        private final List<String> resolvedRefs = new ArrayList<String>();

        private RecordingBoundReferences(BoundReferences delegate) {
            this.delegate = delegate;
        }

        @Override
        public String expose(String structuralAddress, String componentClass) {
            return delegate.expose(structuralAddress, componentClass);
        }

        @Override
        public boolean matches(
                io.github.thisccl.j4a.reference.ResolvedNodeHandle handle,
                String structuralAddress,
                String componentClass) {
            return delegate.matches(handle, structuralAddress, componentClass);
        }

        @Override
        public ReferenceResolution resolve(String publicReference) {
            resolvedRefs.add(publicReference);
            return delegate.resolve(publicReference);
        }

    }

    private static final class Fixture {
        private final ListedHashTree tree;
        private final HashTree childTree;
        private final JmxTestPlan plan;
        private final ProbeElement root;
        private final List<ProbeElement> children;
        private final BoundReferences references;

        private Fixture(
                ListedHashTree tree,
                HashTree childTree,
                JmxTestPlan plan,
                ProbeElement root,
                List<ProbeElement> children,
                BoundReferences references) {
            this.tree = tree;
            this.childTree = childTree;
            this.plan = plan;
            this.root = root;
            this.children = children;
            this.references = references;
        }

        private String ref(ProbeElement element) {
            return io.github.thisccl.j4a.validation.ReferenceTestSupport.expose(references, element);
        }

        private void prepend(ProbeElement element) {
            List<Object> previous = new ArrayList<Object>(childTree.list());
            List<HashTree> subtrees = new ArrayList<HashTree>();
            for (Object child : previous) {
                subtrees.add(childTree.getTree(child));
            }
            childTree.clear();
            childTree.set(element, new ListedHashTree());
            for (int index = 0; index < previous.size(); index++) {
                childTree.set(previous.get(index), subtrees.get(index));
            }
        }
    }

    private static final class ProbeElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;
        private static int instanceCount;

        private ProbeElement(String name) {
            instanceCount++;
            setName(name);
        }
    }
}
