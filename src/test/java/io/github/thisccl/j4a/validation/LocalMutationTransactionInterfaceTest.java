package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalMutationTransactionInterfaceTest {
    private static final List<String> FORBIDDEN_TYPES = Arrays.asList(
            "ElementFactory",
            "JMeterPropertyGraph",
            "StructuredPropertyResolver",
            "PlacementValidator",
            "CandidateReloadObserver",
            "Committer",
            "PreparedState",
            "PreparedReferenceState",
            "PreparedPublication",
            "Callback");

    @Test
    void exposesOnePackageLocalOneShotProductionEntryWithoutLifecycleInjection() {
        assertThat(Modifier.isPublic(LocalMutationTransaction.class.getModifiers())).isFalse();
        assertThat(LocalMutationTransaction.class.getDeclaredMethods())
                .filteredOn(method -> !method.isSynthetic())
                .extracting(method -> method.getName())
                .containsExactly("execute");
        assertThat(LocalMutationTransaction.class.getDeclaredMethods()[0].getParameterTypes())
                .containsExactly(MutationRequest.class);
        assertThat(LocalMutationTransaction.class.getDeclaredMethods()[0].getReturnType())
                .isEqualTo(MutationResult.class);

        for (Constructor<?> constructor : LocalMutationTransaction.class.getDeclaredConstructors()) {
            assertNoForbiddenTypes(constructor.getParameterTypes());
        }
        for (Method method : LocalMutationTransaction.class.getDeclaredMethods()) {
            assertNoForbiddenTypes(method.getParameterTypes());
        }
        for (Field field : MutationRequest.class.getDeclaredFields()) {
            assertThat(forbidden(field.getType())).as(field.toString()).isFalse();
            assertThat(Modifier.isFinal(field.getModifiers())).as(field.toString()).isTrue();
        }
    }

    @Test
    void resultTagsDryRunAndCommitWithPublicationOnlyOnCommit() {
        MutationResult dryRun = MutationResult.dryRunValidated("source", "candidate", 2);
        MutationResult committed = MutationResult.committed(
                "source", "candidate", 2,
                Arrays.asList(new MutationResult.CreatedReference("sampler", "token")),
                Arrays.asList("deleted"));

        assertThat(dryRun.tag()).isEqualTo(MutationResult.Tag.DRY_RUN_VALIDATED);
        assertThat(dryRun.publishedReferences()).isEmpty();
        assertThat(dryRun.receipt().appliedCount()).isEqualTo(2);
        assertThat(committed.tag()).isEqualTo(MutationResult.Tag.COMMITTED);
        assertThat(committed.publishedReferences()).isPresent();
        assertThat(committed.publishedReferences().get().created())
                .extracting(MutationResult.CreatedReference::alias)
                .containsExactly("sampler");
        assertThat(committed.publishedReferences().get().deleted()).containsExactly("deleted");
    }

    @Test
    void transactionIsTheOnlyLifecycleAndUsesNoLegacyInjectedAssemblyOrCatalogPrevalidation()
            throws Exception {
        Path validation = Paths.get(
                "src/main/java/io/github/thisccl/j4a/validation");
        assertThat(validation.resolve("LocalMutationLifecycle.java")).doesNotExist();
        String transaction = read(validation.resolve("LocalMutationTransaction.java"));
        assertThat(transaction)
                .doesNotContain(
                        "new LocalMutationLifecycle",
                        "requirePatchPropertyTypes",
                        "new ApplyPatchExecutor",
                        "localElementFactory(",
                        "StructuredPropertyResolver",
                        "PlacementValidator",
                        "JMeterPropertyGraph")
                .contains("MutationResult execute(MutationRequest request)");

        try (java.util.stream.Stream<Path> files = Files.list(validation)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(LocalMutationTransactionInterfaceTest::read)
                    .filter(source -> source.matches(
                            "(?s).*MutationResult\\s+execute\\s*\\(MutationRequest request\\).*"))
                    .count()).isEqualTo(1L);
        }
    }

    @Test
    void standaloneSetAndApplyEnterOnlyThroughTheTransaction() {
        String mutations = read(Paths.get(
                "src/main/java/io/github/thisccl/j4a/validation/LocalJMeterWorkerMutations.java"));

        assertThat(methodBody(mutations,
                "static String applyPatch(LocalJMeterWorkerRequest request) throws Exception"))
                .contains("execute(request, parsePatch(request.patchYaml()), null)")
                .doesNotContain("ApplyPatchExecutor", "saveValidated(", "LocalJMeterWorkerJmx.load(");
        assertThat(methodBody(mutations,
                "static void setProperty(LocalJMeterWorkerRequest request) throws Exception"))
                .contains("execute(request, setPatch(request), null)")
                .doesNotContain("ApplyPatchExecutor", "saveValidated(", "LocalJMeterWorkerJmx.load(");
        assertThat(methodBody(mutations, "private static MutationResult execute("))
                .contains("new LocalMutationTransaction().execute(")
                .doesNotContain("ApplyPatchExecutor", "saveValidated(", "LocalJMeterFileCommitter");
        assertThat(Paths.get(
                "src/main/java/io/github/thisccl/j4a/validation/StandaloneMutationProgram.java"))
                .doesNotExist();
    }

    @Test
    void sessionSetAndApplyEnterOnlyThroughTheTransaction() {
        String mutations = read(Paths.get(
                "src/main/java/io/github/thisccl/j4a/validation/LocalJMeterWorkerMutations.java"));

        assertThat(methodBody(mutations,
                "static String applyPatch(\n"
                        + "            LocalJMeterWorkerRequest request,\n"
                        + "            SessionReferenceRegistry registry) throws Exception"))
                .contains("execute(request, parsePatch(request.patchYaml()), registry)")
                .doesNotContain("CandidateReloadObserver", "ApplyPatchExecutor", "saveValidated(");
        assertThat(methodBody(mutations,
                "static void setProperty(\n"
                        + "            LocalJMeterWorkerRequest request,\n"
                        + "            SessionReferenceRegistry registry) throws Exception"))
                .contains("execute(request, setPatch(request), registry)")
                .doesNotContain("CandidateReloadObserver", "ApplyPatchExecutor", "saveValidated(");

        assertThat(Paths.get(
                "src/main/java/io/github/thisccl/j4a/validation/LocalJMeterSessionApply.java"))
                .doesNotExist();
    }

    private static void assertNoForbiddenTypes(Class<?>[] types) {
        for (Class<?> type : types) {
            assertThat(forbidden(type)).as(type.getName()).isFalse();
        }
    }

    private static boolean forbidden(Class<?> type) {
        for (String name : FORBIDDEN_TYPES) {
            if (type.getSimpleName().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as(signature).isNotNegative();
        int openingBrace = source.indexOf('{', start);
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(openingBrace, index + 1);
            }
        }
        throw new AssertionError("Unclosed method: " + signature);
    }
}
