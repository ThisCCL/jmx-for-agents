package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCliTestSupport.assertUsageError;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class MainComponentsCategoryDetailsTest {
    @TempDir
    Path tempDir;

    private Path userHome;
    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() throws Exception {
        previousUserHome = System.getProperty("user.home");
        userHome = Files.createDirectories(tempDir.resolve("user-home"));
        System.setProperty("user.home", userHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void categoryDetailsPagesInExactComponentOrderWithoutDuplicatesOrSkips() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = environment(fixtures);

        CliTestResult first = runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "2");
        CliTestResult listing = runMain(environment, "components", "--category", "sampler");
        Map<String, Object> firstPage = mapping(new Yaml().load(first.stdout()));
        CliTestResult repeated = runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "2");
        CliTestResult next = runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "2", "--cursor", String.valueOf(firstPage.get("next_cursor")));
        CliTestResult aliasNext = runMain(environment, "components", "--category", "menu_generative_controller",
                "--details", "true", "--limit", "2", "--cursor", String.valueOf(firstPage.get("next_cursor")));

        assertThat(first.exitCode()).as(first.stderr()).isZero();
        assertThat(repeated.stdout()).isEqualTo(first.stdout());
        assertThat(next.exitCode()).as(next.stderr()).isZero();
        assertThat(aliasNext.stdout()).isEqualTo(next.stdout());
        List<String> firstComponents = components(firstPage);
        List<String> nextComponents = components(mapping(new Yaml().load(next.stdout())));
        assertThat(firstComponents).isSorted().hasSize(2);
        assertThat(nextComponents).isSorted().hasSize(2);
        assertThat(firstComponents).doesNotContainAnyElementsOf(nextComponents);
        List<String> combined = new ArrayList<String>(firstComponents);
        combined.addAll(nextComponents);
        assertThat(combined).isSorted();
        Map<String, Object> listedCategory = mapping(list(mapping(
                new Yaml().load(listing.stdout())).get("categories")).get(0));
        List<String> listed = new ArrayList<String>();
        for (Object value : list(listedCategory.get("components"))) {
            listed.add(String.valueOf(mapping(value).get("component")));
        }
        java.util.Collections.sort(listed);
        assertThat(combined).containsExactlyElementsOf(listed.subList(0, combined.size()));
    }

    @Test
    void categoryDetailsUsesDefaultAndMaximumBoundsAndRejectsInvalidLimits() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = environment(fixtures);

        Map<String, Object> defaultPage = mapping(new Yaml().load(runMain(environment, "components",
                "--category", "sampler", "--details", "true").stdout()));
        CliTestResult maxResult = runMain(environment, "components",
                "--category", "assertion", "--details", "true", "--limit", "50");
        assertThat(maxResult.exitCode()).as(maxResult.stderr()).isZero();
        Map<String, Object> maxPage = mapping(new Yaml().load(maxResult.stdout()));

        assertThat(defaultPage).containsEntry("limit", Integer.valueOf(20));
        assertThat(defaultPage).containsEntry("max_bytes", Integer.valueOf(16384));
        assertThat(list(defaultPage.get("components"))).hasSizeLessThanOrEqualTo(20);
        assertThat(maxPage).containsEntry("limit", Integer.valueOf(50));
        assertThat(maxPage).doesNotContainKey("next_cursor");
        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "0"), "--limit must be between 1 and 50");
        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "51"), "--limit must be between 1 and 50");
        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "not-a-number"), "--limit must be an integer");
        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--max-bytes", "4095"), "--max-bytes must be between 4096 and 65536");
        assertUsageError(runMain(environment, "components", "--max-bytes", "4096"),
                "--max-bytes requires --category with --details true");
        assertUsageError(runMain(environment, "components", "--component-token", ""),
                "--component-token must be a non-empty opaque token");
    }

    @Test
    void categoryDetailsHonorsExactUtf8BudgetAndBindsItIntoContinuation() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = environment(fixtures);
        CliTestResult first = runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "50", "--max-bytes", "4096");
        assertThat(first.exitCode()).as(first.stderr()).isZero();
        assertThat(first.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4096);
        Map<String, Object> page = mapping(new Yaml().load(first.stdout()));
        assertThat(page).containsEntry("max_bytes", Integer.valueOf(4096));
        String cursor = String.valueOf(page.get("next_cursor"));
        assertThat(cursor).startsWith("v4.").hasSizeLessThanOrEqualTo(768);
        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "50", "--max-bytes", "4097",
                "--cursor", cursor), "cursor does not match");
    }

    @Test
    void byteContinuationHasNoGapsAndOversizedTokenRecoversExactOrdinaryDetail() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = environment(fixtures);
        List<String> observed = new ArrayList<String>();
        String cursor = null;
        boolean recoveredOversized = false;
        do {
            List<String> args = new ArrayList<String>(java.util.Arrays.asList(
                    "components", "--category", "sampler", "--details", "true",
                    "--limit", "50", "--max-bytes", "4096"));
            if (cursor != null) {
                args.add("--cursor");
                args.add(cursor);
            }
            CliTestResult result = runMain(environment, args.toArray(new String[args.size()]));
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(4096);
            Map<String, Object> page = mapping(new Yaml().load(result.stdout()));
            for (Object value : list(page.get("components"))) {
                Map<String, Object> entry = mapping(value);
                if (entry.containsKey("error") && !entry.containsKey("component")) {
                    assertThat(entry).containsOnlyKeys("error", "recovery");
                    assertThat(mapping(entry.get("error")))
                            .containsEntry("code", "COMPONENT_DETAIL_TOO_LARGE")
                            .containsEntry("phase", "serialization");
                    String token = String.valueOf(mapping(entry.get("recovery")).get("componentToken"));
                    assertThat(token).startsWith("ct1.").hasSizeLessThanOrEqualTo(512);
                    CliTestResult recovered = runMain(environment,
                            "components", "--component-token", token);
                    assertThat(recovered.exitCode()).as(recovered.stderr()).isZero();
                    Map<String, Object> detail = mapping(new Yaml().load(recovered.stdout()));
                    assertThat(detail).containsOnlyKeys("component", "label", "category", "properties");
                    observed.add(String.valueOf(detail.get("component")));
                    recoveredOversized = true;
                } else {
                    observed.add(String.valueOf(entry.get("component")));
                }
            }
            cursor = page.containsKey("next_cursor") ? String.valueOf(page.get("next_cursor")) : null;
            if (cursor != null) assertThat(cursor).startsWith("v4.").hasSizeLessThanOrEqualTo(768);
        } while (cursor != null);

        Map<String, Object> listing = mapping(new Yaml().load(runMain(environment,
                "components", "--category", "sampler").stdout()));
        Map<String, Object> category = mapping(list(listing.get("categories")).get(0));
        List<String> expected = new ArrayList<String>();
        for (Object item : list(category.get("components"))) {
            expected.add(String.valueOf(mapping(item).get("component")));
        }
        java.util.Collections.sort(expected);
        assertThat(recoveredOversized).isTrue();
        assertThat(observed).containsExactlyElementsOf(expected);
    }

    @Test
    void cursorRejectsMalformedTamperedAndMismatchedBindings() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = environment(fixtures);
        Map<String, Object> page = mapping(new Yaml().load(runMain(environment, "components",
                "--category", "sampler", "--details", "true", "--limit", "2").stdout()));
        String cursor = String.valueOf(page.get("next_cursor"));

        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "2", "--cursor", "malformed"), "Invalid components cursor");
        int macStart = cursor.lastIndexOf('.') + 1;
        String tampered = cursor.substring(0, macStart)
                + (cursor.charAt(macStart) == 'A' ? "B" : "A")
                + cursor.substring(macStart + 1);
        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "2", "--cursor", tampered), "Invalid components cursor");
        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "3", "--cursor", cursor), "cursor does not match");
        assertUsageError(runMain(environment, "components", "--category", "pre-processor",
                "--details", "true", "--limit", "2", "--cursor", cursor), "cursor does not match");
        Map<String, String> otherRuntime = new LinkedHashMap<String, String>();
        otherRuntime.put("JMX_AGENT_JMETER_HOME", fixtures.localHomeWithoutPlugin().toString());
        assertUsageError(runMain(otherRuntime, "components", "--category", "sampler",
                "--details", "true", "--limit", "2", "--cursor", cursor), "Invalid components cursor");
    }

    @Test
    void cursorRejectsRuntimeFingerprintChangeAtTheSameHome() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        Map<String, String> environment = environment(fixtures);
        Map<String, Object> page = mapping(new Yaml().load(runMain(environment, "components",
                "--category", "sampler", "--details", "true", "--limit", "2").stdout()));
        String cursor = String.valueOf(page.get("next_cursor"));
        Path marker = fixtures.localHome().resolve("lib").resolve("ext").resolve("cursor-marker.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(marker))) {
            output.putNextEntry(new JarEntry("cursor-marker.txt"));
            output.write(1);
            output.closeEntry();
        }

        assertUsageError(runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "2", "--cursor", cursor), "Invalid components cursor");
    }

    @Test
    void malformedSigningKeyFailsWithoutPageOrStatePathLeak() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = environment(fixtures);
        Path state = Files.createDirectories(userHome.resolve(".j4a").resolve("state"));
        Path key = state.resolve("components-cursor-signing.key");
        Files.write(key, new byte[31]);

        CliTestResult result = runMain(environment, "components", "--category", "sampler",
                "--details", "true", "--limit", "2");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Components cursor signing key is unavailable")
                .doesNotContain(userHome.toString(), key.toString(), "components-cursor-signing.key",
                        "category:", "components:", "next_cursor:");
    }

    private static Map<String, String> environment(DefaultLocalProfileQaFixtures fixtures) throws Exception {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());
        return environment;
    }

    private static List<String> components(Map<String, Object> page) {
        List<String> components = new ArrayList<String>();
        for (Object value : list(page.get("components"))) {
            components.add(String.valueOf(mapping(value).get("component")));
        }
        return components;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }
}
