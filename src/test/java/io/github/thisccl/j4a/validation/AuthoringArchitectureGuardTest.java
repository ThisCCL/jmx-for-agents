package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathResolver;
import io.github.thisccl.j4a.read.ReadOptions;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.junit.jupiter.api.Test;

class AuthoringArchitectureGuardTest {
    private static final List<Path> LEGACY_ADDRESS_SCAN_ROOTS = Arrays.asList(
            Paths.get("README.md"),
            Paths.get("skills/j4a-master"),
            Paths.get("scripts/qa-property-graph.mjs"),
            Paths.get("scripts/qa-agent-facing-artifacts.mjs"),
            Paths.get("scripts/qa-agent-facing-assertions.mjs"),
            Paths.get("scripts/qa-agent-facing-full-scenario.mjs"),
            Paths.get("scripts/qa-agent-facing-interaction-ux-scenario.mjs"),
            Paths.get("scripts/qa-agent-facing-invalid-template-scenario.mjs"),
            Paths.get("scripts/qa-agent-facing-mcp-session.mjs"),
            Paths.get("scripts/qa-agent-facing-mcp.mjs"),
            Paths.get("scripts/qa-agent-facing-model.mjs"),
            Paths.get("scripts/qa-agent-facing-preflight.mjs"),
            Paths.get("scripts/qa-agent-facing-redaction.mjs"),
            Paths.get("scripts/qa-agent-facing-yaml.mjs"),
            Paths.get("src/main/java"),
            Paths.get("src/main/resources"),
            Paths.get("src/test/java"),
            Paths.get("src/test/resources"));
    private static final Set<Path> LEGACY_ADDRESS_SCAN_ALLOWLIST = new HashSet<Path>(Arrays.asList(
            Paths.get("src/test/java/io/github/thisccl/j4a/validation/AuthoringArchitectureGuardTest.java"),
            Paths.get("src/test/java/io/github/thisccl/j4a/cli/MainCliContractTest.java"),
            Paths.get("src/test/java/io/github/thisccl/j4a/cli/MainComponentsCommandTest.java"),
            Paths.get("src/test/java/io/github/thisccl/j4a/cli/MainHelpTest.java"),
            Paths.get("src/test/java/io/github/thisccl/j4a/cli/ReadCommandYamlTest.java"),
            Paths.get("src/test/java/io/github/thisccl/j4a/mcp/J4aMcpReadOnlyToolsTest.java"),
            Paths.get("src/test/java/io/github/thisccl/j4a/mcp/McpArrayAddressRowsStdioIntegrationTest.java"),
            Paths.get("src/test/java/io/github/thisccl/j4a/mcp/J4aMcpToolSurfaceTest.java"),
            Paths.get("src/test/resources/fixtures/array-address/legacy-string-address.yaml")));
    private static final List<Pattern> FORBIDDEN_LEGACY_ADDRESS_MARKERS = Arrays.asList(
            Pattern.compile("PropertyAddressMode"),
            Pattern.compile("PropertyAddressRenderingException"),
            Pattern.compile("PropertyAddressCliRecovery"),
            Pattern.compile("PROPERTY_ADDRESS_REQUIRES_SEGMENTS"),
            Pattern.compile("--property-address"),
            Pattern.compile("(?:\\\"|'|\\\\\\\")propertyAddress(?:\\\"|'|\\\\\\\")"),
            Pattern.compile("propertyAddress=segments"),
            Pattern.compile("canonical property strings?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("canonical address", Pattern.CASE_INSENSITIVE),
            Pattern.compile("typed[- ]segment array", Pattern.CASE_INSENSITIVE),
            Pattern.compile("escaped property paths?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(?:-\\s*)?property:\\s*[A-Za-z][A-Za-z0-9_]*(?:\\\\\\.|\\.)[A-Za-z]",
                    Pattern.MULTILINE),
            Pattern.compile("property\\s*:\\s*\\[\\s*\\{\\s*[\\\"']?(?:property|key|index)[\\\"']?\\s*:",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
    private static final Set<String> RETIRED_TYPES = new HashSet<String>(Arrays.asList(
            "StructuredCollectionRegistry",
            "StructuredCollectionAdapter",
            "LocalStructuredPropertyDescriptor",
            "LocalStructuredPropertyDiscovery",
            "StructuredCollectionRuntimeContractProbe",
            "ElementFactory",
            "StructuredPropertyResolver",
            "PlacementValidator",
            "CandidateReloadObserver",
            "SetCommitter",
            "InitCommitter",
            "JMeterPropertyGraph",
            "ApplyPatchExecutor",
            "ExactApplyPatchExecutor",
            "LocalJMeterSessionApply",
            "StandaloneMutationProgram",
            "LocalCollectionValueTemplates",
            "LocalJMeterGuiComponentFactory",
            "LocalJMeterGuiComponents",
            "ArgumentsPropertyBinding",
            "ArgumentsPanelDiscovery",
            "PreparedWorkerState",
            "CommitAction",
            "ThrowingSupplier"));

    @Test
    void productionSourcesSatisfyStructuralAuthoringInvariants() throws Exception {
        List<CompilationUnit> units = productionUnits();
        ArchitectureFacts facts = ArchitectureFacts.collect(units);
        List<String> violations = new ArrayList<String>();
        for (CompilationUnit unit : units) {
            inspect(unit, facts, violations);
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void repositoryHasNoRetiredPropertyAddressProtocolMarkers() throws Exception {
        List<String> violations = new ArrayList<String>();
        for (Path root : LEGACY_ADDRESS_SCAN_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted()
                        .collect(Collectors.toList())) {
                    Path repositoryPath = Paths.get("").toAbsolutePath().normalize()
                            .relativize(file.toAbsolutePath().normalize());
                    if (LEGACY_ADDRESS_SCAN_ALLOWLIST.contains(repositoryPath)) {
                        continue;
                    }
                    String content = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                    violations.addAll(forbiddenLegacyAddressMarkers(repositoryPath, content));
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void legacyAddressMarkerGateIsFocusedAndAllowsUnrelatedCanonicalConcepts() {
        assertThat(forbiddenLegacyAddressMarkers(Paths.get("Seed.java"),
                "String option = \"--property-address\";"))
                .anyMatch(value -> value.contains("--property-address"));
        assertThat(forbiddenLegacyAddressMarkers(Paths.get("seed.yaml"),
                "property: [{property: HeaderManager.headers}]"))
                .anyMatch(value -> value.contains("property\\s*:\\s*\\["));
        assertThat(forbiddenLegacyAddressMarkers(Paths.get("Runtime.java"),
                "canonical runtime home; canonical filesystem path; canonical signature; canonical ref"))
                .isEmpty();
    }

    @Test
    void compiledSurfaceHasNoInjectableGuiOrAlternateMutationLifecycle() throws Exception {
        assertThat(LocalJMeterGuiOpenabilityValidator.class.getDeclaredClasses()).isEmpty();
        assertThat(LocalJMeterGuiOpenabilityValidator.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("validate"))
                .allSatisfy(method -> assertThat(method.getParameterTypes()).isIn(
                        new Class<?>[] {JmxTestPlan.class, String.class},
                        new Class<?>[] {JmxTestPlan.class, String.class, MutationImpact.class}));

        assertThat(Paths.get("build/classes/java/main/io/github/thisccl/j4a/validation/"
                + "LocalJMeterGuiComponents.class")).doesNotExist();
        assertThat(LocalJMeterGuiOpenabilityValidator.class.getDeclaredMethods())
                .filteredOn(method -> JMeterGUIComponent.class.isAssignableFrom(method.getReturnType()))
                .allSatisfy(method -> assertThat(Modifier.isPrivate(method.getModifiers()))
                        .as(method.toString()).isTrue());
        for (Class<?> type : compiledValidationClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                if (JMeterGUIComponent.class.isAssignableFrom(method.getReturnType())) {
                    assertThat(Modifier.isPrivate(method.getModifiers()))
                            .as(type.getName() + "#" + method.getName()).isTrue();
                }
            }
        }

        assertThat(LocalMutationTransaction.class.getDeclaredMethods())
                .filteredOn(method -> !method.isSynthetic())
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("execute");
                    assertThat(method.getParameterTypes()).containsExactly(MutationRequest.class);
                    assertThat(method.getReturnType()).isEqualTo(MutationResult.class);
                });
        assertThat(LocalComponentProperties.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("properties")
                        || method.getName().equals("propertiesWithMetadata"))
                .allSatisfy(method -> assertThat(method.getParameterTypes()).doesNotContain(String.class));

        for (String retired : RETIRED_TYPES) {
            assertThat(Paths.get("build/classes/java/main/io/github/thisccl/j4a/validation/"
                    + retired + ".class")).doesNotExist();
        }
    }

    @Test
    void publicReadAndWorkerContractsHaveNoAddressMode() {
        assertThat(Arrays.stream(ReadOptions.class.getDeclaredMethods())
                .map(Method::getName)).doesNotContain("propertyAddressMode");

        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.renderReadData(
                Paths.get("input.jmx"), Paths.get("jmeter-home"), "1", null, "ALL", "false");
        String json = request.toJsonLine();

        assertThat(json).doesNotContain("propertyAddressMode");
        assertThatThrownBy(() -> LocalJMeterWorkerRequest.fromJsonLine(
                json.replaceFirst("\\{", "{\"propertyAddressMode\":\"segments\",")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("propertyAddressMode");
    }

    @Test
    void propertyPathInternalsHaveNoRetiredStringGrammar() throws Exception {
        assertThat(Arrays.stream(PropertyPath.class.getDeclaredMethods())
                .map(Method::getName)).doesNotContain("parse");
        assertThat(Arrays.stream(PropertyPathResolver.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> method.getName().equals("set")
                        || method.getName().equals("requireScalar")))
                .allSatisfy(method -> assertThat(method.getParameterTypes().length < 2
                                || !String.class.equals(method.getParameterTypes()[1]))
                        .as(method.toString()).isTrue());

        assertThat(Paths.get("src/main/java/io/github/thisccl/j4a/path/PropertyPathFormatter.java"))
                .doesNotExist();
        assertThat(new String(Files.readAllBytes(Paths.get(
                        "src/test/java/io/github/thisccl/j4a/path/TestPropertyPaths.java")),
                        java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("parse(", "PropertyPath.parse", "PropertyPathFormatter");
        assertThat(new String(Files.readAllBytes(Paths.get(
                        "src/test/java/io/github/thisccl/j4a/path/PropertyPathTest.java")),
                        java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("PropertyPath.parse", "PropertyPathFormatter");
    }

    @Test
    void publicAddressAcceptingApisUseOnlyTheUnresolvedScalarArrayModel() throws Exception {
        assertThat(Arrays.stream(ComponentCatalog.ComponentProperty.class.getConstructors()))
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes()[0])
                        .as(constructor.toString())
                        .isEqualTo(PropertyPath.class));
        assertThat(Arrays.stream(ComponentCatalog.ComponentProperty.class.getMethods())
                .filter(method -> method.getDeclaringClass()
                        .equals(ComponentCatalog.ComponentProperty.class))
                .map(Method::getName))
                .doesNotContain("property");
        assertThat(ComponentCatalog.ComponentProperty.class.getMethod("address").getReturnType())
                .isEqualTo(List.class);

        assertThat(Arrays.stream(ApplyPatchParser.class.getMethods())
                .filter(method -> method.getName().equals("parseStructuredValue")))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.getParameterTypes()[1])
                        .as(method.toString())
                        .isEqualTo(PropertyAddress.class));
    }

    @Test
    void controlledFixturesCatchRenamedRoutingLoadingInjectionAndAlternateCommitShapes() {
        assertViolation("IdentityBranch", "component-property-identity-branch",
                "Object choose(String a, GraphNode b) {"
                        + " if (a.equals(known()) && b.path().equals(expected())) return shape();"
                        + " return null; }");
        assertViolation("RenamedGraphNodeIdentity", "component-property-identity-branch",
                "Object choose(String property, GraphNode actualProperty) {"
                        + " if (property.equals(actualProperty.path())) return shape();"
                        + " return null; }");
        assertViolation("AliasedGraphNodeIdentity", "component-property-identity-branch",
                "Object choose(String value, GraphNode actualProperty) {"
                        + " GraphNode alias = actualProperty;"
                        + " if (value.equals(alias.path())) return shape();"
                        + " return null; }");
        assertNoViolation("TargetBoundPropertyEquality",
                "Object resolve(String value, LocalJMeterGuiSemanticMetadata.StructuredRowConsumer consumer) {"
                        + " LocalJMeterGuiSemanticMetadata.StructuredRowConsumer selected = consumer;"
                        + " if (value.equals(selected.property()) && selected.evidence() != null) return shape();"
                        + " return null; }");
        assertViolation("TemplateTable", "identity-template-routing-map",
                "void cache(String a, GraphNode b) { java.util.Map<String,Object> x = new java.util.HashMap<>();"
                        + " x.put(a + b.path(), RecursiveValue.collection(null, null)); }");
        assertViolation("LocalTemplateTable", "identity-template-routing-map",
                "void cache(String a, GraphNode b) { java.util.Map<Object,Object> x = new java.util.HashMap<>();"
                        + " Object key = a + b.path(); Object value = RecursiveValue.collection(null, null);"
                        + " x.put(key, value); }");
        assertViolation("LocalTemplateReturn", "identity-template-routing-return",
                "Object choose(String a, GraphNode b) { Object component = a; Object property = b.path();"
                        + " Object value = RecursiveValue.collection(null, null);"
                        + " return route(component, property, value); }");
        assertViolation("CallerRowLoader", "caller-row-class-loading",
                "Class<?> load(Request r) throws Exception { return Class.forName(r.rowType()); }");
        assertViolation("LocalCallerRowLoader", "caller-row-class-loading",
                "Class<?> load(Request r) throws Exception { String implementation = r.rowType();"
                        + " return Class.forName(implementation); }");
        assertViolation("InjectedProbe", "injected-lifecycle-collaborator",
                "void validate(JmxTestPlan p, String h, MutationImpact i, HiddenHook z) {}"
                        + " interface HiddenHook { void run(); }");
        assertViolation("HiddenFactory", "accessible-lifecycle-callback",
                "interface Maker { JMeterGUIComponent make(TestElement target, String name); }");
        assertViolation("HiddenSupplier", "accessible-lifecycle-callback",
                "void probe(java.util.function.Supplier<JMeterGUIComponent> supplier) {}");
        assertViolation("HiddenCommitter", "accessible-lifecycle-callback",
                "interface Publisher { void publish(Path candidate, Path target); }");
        assertViolation("HiddenReloader", "accessible-lifecycle-callback",
                "interface Loader { JmxTestPlan reload(Path source); }");
        assertViolation("AlternateResult", "alternate-mutation-result-entry",
                "MutationResult finish() { return null; }");
        assertViolation("AlternateCommit", "alternate-file-commit-entry",
                "void finish(Path a, Path b) throws Exception { LocalJMeterFileCommitter.commit(a,b,true); }");
        assertViolation("AlternateMove", "alternate-file-publication-entry",
                "void finish(Path a, Path b) throws Exception {"
                        + " Files.move(a, b, StandardCopyOption.REPLACE_EXISTING); }");
        assertViolation("AlternateCopy", "alternate-file-publication-entry",
                "void finish(Path a, Path b) throws Exception {"
                        + " Files.copy(a, b, StandardCopyOption.REPLACE_EXISTING); }");
        assertViolation("AlternateCreateLink", "alternate-file-publication-entry",
                "void finish(Path a, Path b) throws Exception {"
                        + " Path source = a; Path target = b; Files.createLink(target, source); }");
        assertNoViolation("RuntimeObservation",
                "Object observe(GraphNode n) { return n.type(); }"
                        + " void write(RuntimeStructuredRowWriter w) { w.toString(); }"
                        + " Class<?> runtime() throws Exception { return Class.forName(\"java.lang.String\"); }"
                        + " java.util.Map<Object,Object> seen() { return new java.util.IdentityHashMap<>(); }");
        assertNoViolation("LocalJMeterWorkerOperations",
                "void sourceSnapshot(java.io.InputStream input, Path temporary) throws Exception {"
                        + " Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING); }");
    }

    @Test
    void controlledCreateLinkFixturesRejectDirectUtilityStaticImportAndMethodReferenceForms() {
        assertViolation("AlternateCreateLinkUtility", "alternate-file-publication-entry",
                "static final class PublicationUtility {"
                        + " static Path publish(Path a, Path b) throws Exception {"
                        + " Path source = a; Path target = b; return Files.createLink(target, source); } }");
        assertViolation("AlternateCreateLinkFactory", "alternate-file-publication-entry",
                "static final class PublicationFactory {"
                        + " Path publish(Path a, Path b) throws Exception {"
                        + " Path source = a; Path target = b; return Files.createLink(target, source); } }");
        assertViolationWithImports("AlternateStaticCreateLink", "alternate-file-publication-entry",
                "import static java.nio.file.Files.createLink;\n",
                "Path finish(Path a, Path b) throws Exception {"
                        + " Path source = a; Path target = b; return createLink(target, source); }");
        assertViolation("AlternateCreateLinkMethodReference", "alternate-file-publication-entry",
                "interface Linker { Path link(Path target, Path source) throws Exception; }"
                        + " Path finish(Path a, Path b) throws Exception {"
                        + " Linker publisher = Files::createLink; return publisher.link(b, a); }");
        assertNoViolation("UnrelatedHardLinkUtility",
                "static final class OtherFiles {"
                        + " static Path createLink(Path target, Path source) { return target; } }"
                        + " Path observe(Path a, Path b) { return OtherFiles.createLink(b, a); }");
    }

    @Test
    void productionCreateLinkPublicationIsOwnedByConcreteCommitterCommitRole() throws Exception {
        List<String> owners = new ArrayList<String>();
        for (CompilationUnit unit : productionUnits()) {
            for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
                if (isNioFilesMethod(call, unit, "createLink")) {
                    owners.add(enclosingClass(call) + "#" + enclosingMethod(call));
                }
            }
        }

        assertThat(owners).containsExactly("LocalJMeterFileCommitter#commit");
    }

    @Test
    void guiSemanticReflectionRemainsNarrowGenericAndWorkerInternal() throws Exception {
        Path root = Paths.get("src/main/java/io/github/thisccl/j4a/validation");
        List<Path> bindingOwners = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted().collect(Collectors.toList())) {
                String content = new String(Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (content.contains("org.apache.jmeter.gui.BindingGroup")
                        || content.contains("org.apache.jorphan.gui.ObjectTableModel")) {
                    bindingOwners.add(file);
                }
            }
        }

        assertThat(bindingOwners).containsExactly(
                root.resolve("LocalJMeterGuiSemanticTraversal.java"));
        for (String file : Arrays.asList(
                "LocalJMeterGuiSemanticTraversal.java",
                "LocalJMeterGuiSemanticCorrelation.java",
                "LocalJMeterGuiSemanticMetadata.java")) {
            String content = new String(Files.readAllBytes(root.resolve(file)),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(content).doesNotContain(
                    "HTTPSampler.domain", "HTTPSampler.port", "HTTPsampler.Arguments",
                    "org.apache.jmeter.protocol.http.util.HTTPArgument");
        }
        assertThat(Modifier.isPublic(LocalJMeterGuiSemanticMetadata.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(ComponentCatalog.ComponentDefinition.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(type -> type.startsWith("java.lang.reflect.")
                        || type.startsWith("java.awt.") || type.startsWith("javax.swing."));
    }

    private static void inspect(
            CompilationUnit unit, ArchitectureFacts facts, List<String> violations) {
        boolean guardLifecycleCallbacks = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString()
                        .equals("io.github.thisccl.j4a.validation"))
                .orElse(true);
        for (ClassOrInterfaceDeclaration declaration
                : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (RETIRED_TYPES.contains(declaration.getNameAsString())) {
                violations.add(location(declaration) + ":retired-type:" + declaration.getNameAsString());
            }
            if (facts.mutationResultClasses.contains(declaration.getNameAsString())) {
                for (FieldDeclaration field : declaration.getFields()) {
                    if (facts.interfaces.contains(simpleName(field.getElementType().asString()))) {
                        violations.add(location(field) + ":mutation-callback-field:"
                                + field.getElementType());
                    }
                }
            }
            if (guardLifecycleCallbacks && (declaration.isInterface() || declaration.isAbstract())
                    && !declaration.isPrivate()
                    && declaration.getMethods().stream().anyMatch(AuthoringArchitectureGuardTest::callbackShape)) {
                violations.add(location(declaration) + ":accessible-lifecycle-callback:"
                        + declaration.getNameAsString());
            }
        }
        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            if (RETIRED_TYPES.contains(type.getNameAsString())) {
                violations.add(location(type) + ":retired-type-reference:" + type.getNameAsString());
            }
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            String className = enclosingClass(method);
            if (!method.isPrivate()
                    && "JMeterGUIComponent".equals(simpleName(method.getTypeAsString()))) {
                violations.add(location(method) + ":accessible-gui-construction-method:"
                        + className + "#" + method.getNameAsString());
            }
            if ("MutationResult".equals(simpleName(method.getTypeAsString()))
                    && !allowedMutationResultMethod(className, method.getNameAsString())) {
                violations.add(location(method) + ":alternate-mutation-result-entry:"
                        + className + "#" + method.getNameAsString());
            }
            if ("validate".equals(method.getNameAsString()) && hasParameter(method, "JmxTestPlan")) {
                for (Parameter parameter : method.getParameters()) {
                    if (!Arrays.asList("JmxTestPlan", "String", "MutationImpact")
                            .contains(simpleName(parameter.getTypeAsString()))) {
                        violations.add(location(parameter) + ":injected-lifecycle-collaborator:"
                                + parameter.getType());
                    }
                }
            }
            for (Parameter parameter : method.getParameters()) {
                if (guardLifecycleCallbacks && lifecycleCallbackType(parameter.getTypeAsString(), facts)) {
                    violations.add(location(parameter) + ":accessible-lifecycle-callback:"
                            + parameter.getType());
                }
            }
        }
        for (IfStmt statement : unit.findAll(IfStmt.class)) {
            if (hasIdentityRouting(domains(statement.getCondition()))
                    && hasEquality(statement.getCondition())) {
                violations.add(location(statement) + ":component-property-identity-branch");
            }
        }
        for (SwitchStmt statement : unit.findAll(SwitchStmt.class)) {
            if (hasIdentityRouting(domains(statement.getSelector()))) {
                violations.add(location(statement) + ":component-property-identity-switch");
            }
        }
        for (ReturnStmt statement : unit.findAll(ReturnStmt.class)) {
            if (statement.getExpression().isPresent()) {
                EnumSet<IdentityDomain> returned = domains(statement.getExpression().get());
                if (hasIdentityRouting(returned) && returned.contains(IdentityDomain.TEMPLATE)) {
                    violations.add(location(statement) + ":identity-template-routing-return");
                }
            }
        }
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if ("forName".equals(call.getNameAsString())
                    && call.getScope().map(Object::toString).orElse("").endsWith("Class")
                    && !call.getArguments().isEmpty()
                    && domains(call.getArgument(0)).contains(IdentityDomain.ROW)) {
                violations.add(location(call) + ":caller-row-class-loading");
            }
            if ("put".equals(call.getNameAsString()) && call.getArguments().size() >= 2) {
                EnumSet<IdentityDomain> keyDomains = domains(call.getArgument(0));
                EnumSet<IdentityDomain> valueDomains = domains(call.getArgument(1));
                if (hasIdentityRouting(keyDomains) || valueDomains.contains(IdentityDomain.TEMPLATE)) {
                    violations.add(location(call) + ":identity-template-routing-map");
                }
            }
            if ("commit".equals(call.getNameAsString())
                    && call.getScope().map(Object::toString).orElse("")
                            .endsWith("LocalJMeterFileCommitter")) {
                String className = enclosingClass(call);
                String methodName = enclosingMethod(call);
                if (!allowedFileCommit(className, methodName)) {
                    violations.add(location(call) + ":alternate-file-commit-entry:"
                            + className + "#" + methodName);
                }
            }
            if (isFilesPublication(call, unit)) {
                String className = enclosingClass(call);
                String methodName = enclosingMethod(call);
                if (!allowedFilesPublication(call, unit, className, methodName)) {
                    violations.add(location(call) + ":alternate-file-publication-entry:"
                            + className + "#" + methodName);
                }
            }
        }
        for (MethodReferenceExpr reference : unit.findAll(MethodReferenceExpr.class)) {
            if (isFilesPublication(reference, unit)) {
                String className = enclosingClass(reference);
                String methodName = enclosingMethod(reference);
                if (!allowedFilesPublication(reference.getIdentifier(), unit, className, methodName)) {
                    violations.add(location(reference) + ":alternate-file-publication-entry:"
                            + className + "#" + methodName);
                }
            }
        }
    }

    private static List<CompilationUnit> productionUnits() throws Exception {
        Path root = Paths.get("src/main/java/io/github/thisccl/j4a");
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted().collect(Collectors.toList());
            List<CompilationUnit> units = new ArrayList<CompilationUnit>();
            for (Path file : files) {
                units.add(StaticJavaParser.parse(file));
            }
            return units;
        }
    }

    private static List<String> forbiddenLegacyAddressMarkers(Path file, String content) {
        List<String> violations = new ArrayList<String>();
        for (Pattern marker : FORBIDDEN_LEGACY_ADDRESS_MARKERS) {
            if (marker.matcher(content).find()) {
                violations.add(file + ":legacy-property-address-marker:" + marker.pattern());
            }
        }
        return violations;
    }

    private static List<Class<?>> compiledValidationClasses() throws Exception {
        Path root = Paths.get("build/classes/java/main");
        Path validation = root.resolve("io/github/thisccl/j4a/validation");
        try (Stream<Path> paths = Files.walk(validation)) {
            List<Path> classFiles = paths.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted().collect(Collectors.toList());
            List<Class<?>> classes = new ArrayList<Class<?>>();
            for (Path classFile : classFiles) {
                String relative = root.relativize(classFile).toString();
                String className = relative.substring(0, relative.length() - ".class".length())
                        .replace(java.io.File.separatorChar, '.');
                classes.add(Class.forName(className, false,
                        AuthoringArchitectureGuardTest.class.getClassLoader()));
            }
            return classes;
        }
    }

    private static void assertViolation(String name, String expected, String body) {
        assertThat(fixtureViolations(name, body)).anyMatch(value -> value.contains(expected));
    }

    private static void assertNoViolation(String name, String body) {
        assertThat(fixtureViolations(name, body)).isEmpty();
    }

    private static void assertViolationWithImports(
            String name, String expected, String imports, String body) {
        assertThat(fixtureViolations(name, imports, body)).anyMatch(value -> value.contains(expected));
    }

    private static List<String> fixtureViolations(String name, String body) {
        return fixtureViolations(name, "", body);
    }

    private static List<String> fixtureViolations(String name, String imports, String body) {
        CompilationUnit unit = StaticJavaParser.parse("import java.nio.file.Files;\n"
                + imports + "class " + name + " { " + body + " }");
        ArchitectureFacts facts = ArchitectureFacts.collect(Collections.singletonList(unit));
        List<String> violations = new ArrayList<String>();
        inspect(unit, facts, violations);
        return violations;
    }

    private static EnumSet<IdentityDomain> domains(Expression expression) {
        CallableDeclaration<?> callable = expression.findAncestor(CallableDeclaration.class).orElse(null);
        return callable == null
                ? directDomains(expression, Collections.<String, EnumSet<IdentityDomain>>emptyMap())
                : new DomainFlow(callable).domains(expression);
    }

    private static EnumSet<IdentityDomain> directDomains(
            Expression expression, java.util.Map<String, EnumSet<IdentityDomain>> locals) {
        EnumSet<IdentityDomain> found = EnumSet.noneOf(IdentityDomain.class);
        for (NameExpr name : expression.findAll(NameExpr.class)) {
            EnumSet<IdentityDomain> local = locals.get(name.getNameAsString());
            if (local != null) {
                found.addAll(local);
            }
        }
        for (MethodCallExpr call : expression.findAll(MethodCallExpr.class)) {
            classify(call.getNameAsString(), found);
            if (call.getScope().map(Object::toString).orElse("").endsWith("RecursiveValue")) {
                found.add(IdentityDomain.TEMPLATE);
            }
        }
        return found;
    }

    private static boolean hasEquality(Expression expression) {
        return expression.findAll(BinaryExpr.class).stream()
                        .anyMatch(binary -> binary.getOperator() == BinaryExpr.Operator.EQUALS)
                || expression.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> "equals".equals(call.getNameAsString()));
    }

    private static void classify(String rawName, EnumSet<IdentityDomain> domains) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
        if (name.equals("component") || name.equals("guiclass") || name.equals("menuclass")
                || name.equals("testclass") || name.equals("runtimeclass")) {
            domains.add(IdentityDomain.COMPONENT);
        }
        if (name.equals("property") || name.equals("propertypath") || name.equals("path")) {
            domains.add(IdentityDomain.PROPERTY);
        }
        if (name.equals("rowtype") || name.equals("rowclass")) {
            domains.add(IdentityDomain.ROW);
        }
    }

    private static boolean hasIdentityRouting(EnumSet<IdentityDomain> domains) {
        return domains.contains(IdentityDomain.COMPONENT)
                && domains.contains(IdentityDomain.PROPERTY);
    }

    private static boolean hasParameter(MethodDeclaration method, String type) {
        return method.getParameters().stream()
                .anyMatch(parameter -> type.equals(simpleName(parameter.getTypeAsString())));
    }

    private static boolean callbackShape(MethodDeclaration method) {
        String returnType = simpleName(method.getTypeAsString());
        long pathParameters = method.getParameters().stream()
                .filter(parameter -> "Path".equals(simpleName(parameter.getTypeAsString())))
                .count();
        return "JMeterGUIComponent".equals(returnType)
                || (("JmxTestPlan".equals(returnType) || "TestElement".equals(returnType))
                        && pathParameters > 0)
                || ("void".equals(returnType) && pathParameters > 1);
    }

    private static boolean lifecycleCallbackType(String type, ArchitectureFacts facts) {
        String compact = type.replace(" ", "");
        if (facts.lifecycleCallbackTypes.contains(simpleName(compact))) {
            return true;
        }
        return ((compact.contains("Supplier<") || compact.contains("Callable<"))
                        && compact.contains("JMeterGUIComponent"))
                || (compact.contains("Function<") && compact.contains("Path")
                        && (compact.contains("JmxTestPlan") || compact.contains("TestElement")))
                || ((compact.contains("Consumer<") || compact.contains("BiConsumer<"))
                        && compact.contains("Path"));
    }

    private static boolean isFilesPublication(MethodCallExpr call, CompilationUnit unit) {
        return isNioFilesMethod(call, unit, call.getNameAsString())
                && isFilesPublicationName(call.getNameAsString());
    }

    private static boolean isFilesPublication(MethodReferenceExpr reference, CompilationUnit unit) {
        return isFilesPublicationName(reference.getIdentifier())
                && isNioFilesScope(reference.getScope().toString(), unit);
    }

    private static boolean isFilesPublicationName(String name) {
        return "move".equals(name) || "copy".equals(name) || "createLink".equals(name);
    }

    private static boolean isNioFilesMethod(
            MethodCallExpr call, CompilationUnit unit, String methodName) {
        if (!methodName.equals(call.getNameAsString())) {
            return false;
        }
        if (call.getScope().isPresent()) {
            return isNioFilesScope(call.getScope().get().toString(), unit);
        }
        return hasStaticNioFilesImport(unit, methodName);
    }

    private static boolean isNioFilesScope(String scope, CompilationUnit unit) {
        return "java.nio.file.Files".equals(scope)
                || ("Files".equals(scope) && hasNioFilesImport(unit));
    }

    private static boolean hasNioFilesImport(CompilationUnit unit) {
        return unit.getImports().stream().anyMatch(imported -> !imported.isStatic()
                && ("java.nio.file.Files".equals(imported.getNameAsString())
                        || (imported.isAsterisk()
                                && "java.nio.file".equals(imported.getNameAsString()))));
    }

    private static boolean hasStaticNioFilesImport(CompilationUnit unit, String methodName) {
        return unit.getImports().stream().filter(ImportDeclaration::isStatic).anyMatch(imported ->
                (imported.isAsterisk() && "java.nio.file.Files".equals(imported.getNameAsString()))
                        || ("java.nio.file.Files." + methodName)
                                .equals(imported.getNameAsString()));
    }

    private static boolean allowedFilesPublication(
            MethodCallExpr call, CompilationUnit unit, String className, String methodName) {
        if ("createLink".equals(call.getNameAsString())) {
            return isConcreteCommitterCommitRole(unit, className, methodName);
        }
        if ("move".equals(call.getNameAsString())) {
            return isConcreteCommitterCommitRole(unit, className, methodName)
                    || isTransactionPublicationRole(unit, className);
        }
        return argumentHasType(call, 0, "InputStream") || argumentHasType(call, 1, "OutputStream")
                || isConcreteCommitterCommitRole(unit, className, methodName)
                || isTransactionPublicationRole(unit, className);
    }

    private static boolean allowedFilesPublication(
            String publicationName, CompilationUnit unit, String className, String methodName) {
        return "createLink".equals(publicationName)
                && isConcreteCommitterCommitRole(unit, className, methodName);
    }

    private static boolean isConcreteCommitterCommitRole(
            CompilationUnit unit, String className, String methodName) {
        return isValidationPackage(unit) && "LocalJMeterFileCommitter".equals(className)
                && "commit".equals(methodName);
    }

    private static boolean isTransactionPublicationRole(CompilationUnit unit, String className) {
        return isValidationPackage(unit) && "LocalMutationTransaction".equals(className);
    }

    private static boolean isValidationPackage(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()
                .equals("io.github.thisccl.j4a.validation")).orElse(false);
    }

    private static boolean argumentHasType(MethodCallExpr call, int index, String expectedType) {
        if (call.getArguments().size() <= index || !call.getArgument(index).isNameExpr()) {
            return false;
        }
        String name = call.getArgument(index).asNameExpr().getNameAsString();
        CallableDeclaration<?> callable = call.findAncestor(CallableDeclaration.class).orElse(null);
        if (callable == null) {
            return false;
        }
        if (callable.getParameters().stream().anyMatch(parameter ->
                parameter.getNameAsString().equals(name)
                        && expectedType.equals(simpleName(parameter.getTypeAsString())))) {
            return true;
        }
        return callable.findAll(VariableDeclarator.class).stream().anyMatch(variable ->
                variable.getNameAsString().equals(name)
                        && expectedType.equals(simpleName(variable.getTypeAsString())));
    }

    private static String enclosingClass(Node node) {
        String outermost = "<top>";
        Node current = node;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (current instanceof ClassOrInterfaceDeclaration) {
                outermost = ((ClassOrInterfaceDeclaration) current).getNameAsString();
            }
        }
        return outermost;
    }

    private static String enclosingMethod(Node node) {
        return node.findAncestor(MethodDeclaration.class)
                .map(MethodDeclaration::getNameAsString).orElse("<field>");
    }

    private static String location(Node node) {
        return enclosingClass(node) + "#" + enclosingMethod(node);
    }

    private static String simpleName(String value) {
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static boolean allowedMutationResultMethod(String className, String methodName) {
        return "LocalMutationTransaction".equals(className)
                || ("LocalJMeterWorkerMutations".equals(className) && "execute".equals(methodName))
                || ("MutationResult".equals(className)
                        && ("dryRunValidated".equals(methodName) || "committed".equals(methodName)));
    }

    private static boolean allowedFileCommit(String className, String methodName) {
        return "LocalMutationTransaction".equals(className)
                || ("LocalJMeterWorkerMutations".equals(className) && "initJmx".equals(methodName));
    }

    private enum IdentityDomain {
        COMPONENT,
        PROPERTY,
        ROW,
        TEMPLATE
    }

    private static final class DomainFlow {
        private final CallableDeclaration<?> callable;
        private final java.util.Map<String, EnumSet<IdentityDomain>> locals =
                new java.util.HashMap<String, EnumSet<IdentityDomain>>();
        private final java.util.Map<String, String> localTypes =
                new java.util.HashMap<String, String>();

        private DomainFlow(CallableDeclaration<?> callable) {
            this.callable = callable;
            for (Parameter parameter : callable.getParameters()) {
                EnumSet<IdentityDomain> parameterDomains = EnumSet.noneOf(IdentityDomain.class);
                String parameterType = simpleName(parameter.getTypeAsString());
                localTypes.put(parameter.getNameAsString(), parameterType);
                if ("String".equals(parameterType)) {
                    parameterDomains.add(IdentityDomain.COMPONENT);
                } else if ("GraphNode".equals(parameterType)) {
                    parameterDomains.add(IdentityDomain.PROPERTY);
                }
                if (!parameterDomains.isEmpty()) {
                    locals.put(parameter.getNameAsString(), parameterDomains);
                }
            }
            resolveLocals();
        }

        private EnumSet<IdentityDomain> domains(Expression expression) {
            EnumSet<IdentityDomain> found = directDomains(expression, locals);
            for (MethodCallExpr call : expression.findAll(MethodCallExpr.class)) {
                if (targetBoundPropertySelection(call)
                        && call.getScope().filter(NameExpr.class::isInstance).isPresent()) {
                    String name = call.getScope().get().asNameExpr().getNameAsString();
                    EnumSet<IdentityDomain> scopeDomains = locals.get(name);
                    if (scopeDomains != null && scopeDomains.contains(IdentityDomain.COMPONENT)) {
                        found.remove(IdentityDomain.COMPONENT);
                    }
                }
            }
            return found;
        }

        private boolean targetBoundPropertySelection(MethodCallExpr call) {
            if (!"equals".equals(call.getNameAsString()) || !call.getScope().isPresent()) {
                return false;
            }
            for (Expression argument : call.getArguments()) {
                if (!(argument instanceof MethodCallExpr)) {
                    continue;
                }
                MethodCallExpr property = (MethodCallExpr) argument;
                if (!"property".equals(property.getNameAsString())
                        || !property.getScope().filter(NameExpr.class::isInstance).isPresent()) {
                    continue;
                }
                String consumer = property.getScope().get().asNameExpr().getNameAsString();
                if ("StructuredRowConsumer".equals(localTypes.get(consumer))) {
                    return true;
                }
            }
            return false;
        }

        private void resolveLocals() {
            boolean changed;
            do {
                changed = false;
                for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
                    localTypes.put(variable.getNameAsString(), simpleName(variable.getTypeAsString()));
                    if (variable.getInitializer().isPresent()) {
                        changed |= merge(variable.getNameAsString(),
                                directDomains(variable.getInitializer().get(), locals));
                    }
                }
                for (AssignExpr assignment : callable.findAll(AssignExpr.class)) {
                    if (assignment.getTarget().isNameExpr()) {
                        changed |= merge(assignment.getTarget().asNameExpr().getNameAsString(),
                                directDomains(assignment.getValue(), locals));
                    }
                }
            } while (changed);
        }

        private boolean merge(String name, EnumSet<IdentityDomain> discovered) {
            if (discovered.isEmpty()) {
                return false;
            }
            EnumSet<IdentityDomain> existing = locals.get(name);
            if (existing == null) {
                locals.put(name, EnumSet.copyOf(discovered));
                return true;
            }
            int before = existing.size();
            existing.addAll(discovered);
            return existing.size() != before;
        }
    }

    private static final class ArchitectureFacts {
        private final Set<String> interfaces = new HashSet<String>();
        private final Set<String> mutationResultClasses = new HashSet<String>();
        private final Set<String> lifecycleCallbackTypes = new HashSet<String>();

        static ArchitectureFacts collect(List<CompilationUnit> units) {
            ArchitectureFacts facts = new ArchitectureFacts();
            for (CompilationUnit unit : units) {
                for (ClassOrInterfaceDeclaration declaration
                        : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (declaration.isInterface()) {
                        facts.interfaces.add(declaration.getNameAsString());
                        if (declaration.getMethods().stream()
                                .anyMatch(AuthoringArchitectureGuardTest::callbackShape)) {
                            facts.lifecycleCallbackTypes.add(declaration.getNameAsString());
                        }
                    }
                    if (declaration.getMethods().stream().anyMatch(method ->
                            "MutationResult".equals(simpleName(method.getTypeAsString())))) {
                        facts.mutationResultClasses.add(declaration.getNameAsString());
                    }
                }
            }
            return facts;
        }
    }
}
