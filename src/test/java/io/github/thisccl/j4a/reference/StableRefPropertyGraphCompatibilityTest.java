package io.github.thisccl.j4a.reference;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchCompiler;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.apply.ResolvedApplyPlan;
import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import io.github.thisccl.j4a.cli.J4aCommandExecutor;
import io.github.thisccl.j4a.mcp.McpCommandResultAdapter;
import io.github.thisccl.j4a.path.PropertyAddress;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class StableRefPropertyGraphCompatibilityTest {
    @Test
    void genericPropertyDocumentPassesUnchangedWhenReferenceResolvesToNodeHandle() {
        ProbeElement target = new ProbeElement("target");
        Map<String, Object> recursiveValue = new LinkedHashMap<String, Object>();
        recursiveValue.put("presence", "present");
        recursiveValue.put("items", Arrays.<Object>asList("first", Collections.singletonMap("nested", "second")));
        PropertyAddress propertyAddress = new PropertyAddress(Collections.<Object>singletonList(
                "config\\.element.properties['a\\.b'][1].name"));
        ApplyPatch.PropertyChange property =
                new ApplyPatch.PropertyChange(propertyAddress, recursiveValue, ApplyPatch.ValueType.RAW);
        ApplyPatch patch = new ApplyPatch(Collections.singletonList(new ApplyPatch.Change(
                new ApplyPatch.SetOperation(
                        "opaque-session-ref",
                        Optional.of("$literal-component"),
                        Collections.singletonList(property)))));
        RecordingReferences references = new RecordingReferences(target);

        ResolvedApplyPlan compiled = new ApplyPatchCompiler().compile(patch, references);

        ResolvedApplyPlan.SetOperation set =
                (ResolvedApplyPlan.SetOperation) compiled.changes().get(0).operation();
        ResolvedApplyPlan.InputNodeReference ref = (ResolvedApplyPlan.InputNodeReference) set.ref();
        assertThat(references.resolved()).containsExactly("opaque-session-ref");
        assertThat(io.github.thisccl.j4a.validation.ReferenceTestSupport.element(ref.handle())).isSameAs(target);
        assertThat(set.component()).contains("$literal-component");
        assertThat(set.properties()).singleElement().isSameAs(property);
        assertThat(set.properties().get(0).property()).isEqualTo(propertyAddress);
        assertThat(set.properties().get(0).value()).isSameAs(recursiveValue);
    }

    @Test
    void dollarPrefixedNonReferenceFieldsRemainLiteral() {
        ApplyPatch patch = new ApplyPatchParser().parse("changes:\n"
                + "  - set:\n"
                + "      ref: ordinary-ref\n"
                + "      component: $literal-component\n"
                + "      properties:\n"
                + "        - property: [$literal-path]\n"
                + "          value: $literal-value\n"
                + "          type: raw\n");

        ApplyPatch.SetOperation set = (ApplyPatch.SetOperation) patch.changes().get(0).operation();

        assertThat(set.refExpression()).isInstanceOf(ApplyPatch.OrdinaryReference.class);
        assertThat(set.component()).contains("$literal-component");
        assertThat(set.properties().get(0).property()).isEqualTo(
                new PropertyAddress(Collections.<Object>singletonList("$literal-path")));
        assertThat(set.properties().get(0).value()).isEqualTo("$literal-value");
    }

    @Test
    void opaqueDigestSurvivesWorkerReceiptAndMcpIdentityProjection() throws Exception {
        String workerText = "appliedCount: 2\n"
                + "opaqueDigest: sha256:replacement-digest\n"
                + "createdRefs:\n"
                + "- {alias: created, ref: abcdefghijklmnop}\n"
                + "deletedRefs: [deleted123456789]\n"
                + "changeResults:\n"
                + "- {index: 0, operation: add, status: committed, context: {alias: created, component: example.Component, parent: parent, position: last, properties: []}, resultRef: abcdefghijklmnop}\n"
                + "- {index: 1, operation: delete, status: committed, context: {ref: deleted123456789}}\n";
        Map<String, Object> commandData = structuredApplyData(workerText);
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(success(workerText, commandData));

        assertThat(commandData.keySet()).containsSubsequence(
                "opaqueDigest", "writeMode", "appliedCount", "createdRefs", "deletedRefs",
                "changeResults");
        assertThat(commandData).containsEntry("opaqueDigest", "sha256:replacement-digest");
        assertThat(mapping(adapted.get("structuredContent")).get("data")).isEqualTo(commandData);
        assertThat(mapping(list(adapted.get("content")).get(0)).get("text")).isEqualTo(workerText);
    }

    @Test
    void referencePackageContainsNoPropertyGraphAdmissionCodecOrPathBehavior() throws Exception {
        Path referencePackage = Paths.get(System.getProperty("user.dir"),
                "src/main/java/io/github/thisccl/j4a/reference");
        try (Stream<Path> files = Files.list(referencePackage)) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                String name = file.getFileName().toString();
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertThat(name).doesNotContain("PropertyGraph", "PropertyPath", "Codec", "Admission");
                assertThat(source).doesNotContain(
                        "JMeterPropertyGraph", "PropertyPath", "PropertyGraphCodec", "PropertyAdmission");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structuredApplyData(String workerText) throws Exception {
        Method structuredData = J4aCommandExecutor.class.getDeclaredMethod(
                "structuredData", String[].class, String.class);
        structuredData.setAccessible(true);
        return (Map<String, Object>) structuredData.invoke(
                null,
                new Object[] {new String[] {"apply", "input.jmx", "--patch", "-", "--override"}, workerText});
    }

    private static CommandResult success(String text, Map<String, Object> data) {
        return new CommandResult() {
            @Override public int exitCode() { return 0; }
            @Override public String textOutput() { return text; }
            @Override public Map<String, Object> structuredData() { return data; }
            @Override public List<CommandDiagnostic> diagnostics() { return Collections.emptyList(); }
            @Override public String recoveryGuidance() { return null; }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static final class RecordingReferences implements BoundReferences {
        private final TestElement target;
        private final List<String> resolved = new java.util.ArrayList<String>();

        private RecordingReferences(TestElement target) {
            this.target = target;
        }

        @Override public String expose(String structuralAddress, String componentClass) {
            return "opaque-session-ref";
        }

        @Override public boolean matches(
                ResolvedNodeHandle handle, String structuralAddress, String componentClass) {
            return io.github.thisccl.j4a.validation.ReferenceTestSupport.element(handle) == target;
        }

        @Override public ReferenceResolution resolve(String publicReference) {
            resolved.add(publicReference);
            return ReferenceResolution.resolved(io.github.thisccl.j4a.validation.ReferenceTestSupport.handle(target));
        }

        private List<String> resolved() {
            return Collections.unmodifiableList(resolved);
        }
    }

    private static final class ProbeElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;

        private ProbeElement(String name) {
            setName(name);
        }
    }
}
