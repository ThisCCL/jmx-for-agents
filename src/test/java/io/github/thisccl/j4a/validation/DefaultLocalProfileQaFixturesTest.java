package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class DefaultLocalProfileQaFixturesTest {
    @Test
    void compatibleFixtureRequestsReuseCachedSourcesAndReturnIsolatedHomes() throws Exception {
        DefaultLocalProfileQaFixtures first = DefaultLocalProfileQaFixtures.cached();
        DefaultLocalProfileQaFixtures second = DefaultLocalProfileQaFixtures.cached();

        Path firstHome = first.localHome();
        Path secondHome = second.localHome();
        Path cacheRoot = DefaultLocalProfileHomeFixtures.cacheRootForTesting();
        Path cacheSourceHome = DefaultLocalProfileHomeFixtures.cacheSourceHomeForTesting("local-home");
        Path firstPlugin = firstHome.resolve("lib").resolve("ext").resolve("synthetic-ext-plugin.jar");
        Path secondPlugin = secondHome.resolve("lib").resolve("ext").resolve("synthetic-ext-plugin.jar");
        Path cachePlugin = cacheSourceHome.resolve("lib").resolve("ext").resolve("synthetic-ext-plugin.jar");
        String firstPluginHash = DefaultLocalProfileHomeFixtures.fileDigestForTesting(firstPlugin);
        String secondPluginHash = DefaultLocalProfileHomeFixtures.fileDigestForTesting(secondPlugin);
        String cachePluginHash = DefaultLocalProfileHomeFixtures.fileDigestForTesting(cachePlugin);
        String cacheSourceHash = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);

        assertThat(secondHome).isNotEqualTo(firstHome);
        assertThat(firstHome.startsWith(cacheRoot)).isFalse();
        assertThat(secondHome.startsWith(cacheRoot)).isFalse();
        assertThat(secondPlugin).isNotEqualTo(firstPlugin);
        assertThat(secondPluginHash).isEqualTo(firstPluginHash).isEqualTo(cachePluginHash);
        writeEvidence("add-java-mcp-server-and-test-caching-task-6-remediation-cache.log",
                "PASS compatible cached fixture source reuse with isolated homes" + System.lineSeparator()
                        + "firstHome=" + firstHome + System.lineSeparator()
                        + "secondHome=" + secondHome + System.lineSeparator()
                        + "cacheRoot=" + cacheRoot + System.lineSeparator()
                        + "cacheSourceHome=" + cacheSourceHome + System.lineSeparator()
                        + "firstPluginSha256=" + firstPluginHash + System.lineSeparator()
                        + "secondPluginSha256=" + secondPluginHash + System.lineSeparator()
                        + "cachePluginSha256=" + cachePluginHash + System.lineSeparator()
                        + "cacheSourceSha256=" + cacheSourceHash + System.lineSeparator());
    }

    @Test
    void freshFixtureWorkerOutputUsesInstanceOutAndDoesNotMutateCachedSource() throws Exception {
        DefaultLocalProfileQaFixtures cached = DefaultLocalProfileQaFixtures.cached();
        cached.localHome();
        Path cacheSourceHome = DefaultLocalProfileHomeFixtures.cacheSourceHomeForTesting("local-home");
        String cacheSourceBefore = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);
        DefaultLocalProfileQaFixtures first = DefaultLocalProfileQaFixtures.fresh();
        DefaultLocalProfileQaFixtures second = DefaultLocalProfileQaFixtures.fresh();

        Path firstHome = first.localHome();
        Path secondHome = second.localHome();
        Path mutableOutput = first.root().resolve("out").resolve("fresh-worker-output.jmx");
        LocalJMeterWorkerResult result = LocalJMeterWorkerClient.withTimeouts(
                java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(60),
                java.time.Duration.ofSeconds(60))
                .execute(LocalJMeterWorkerRequest.applyPatch(
                        first.root().resolve("basic.jmx"),
                        firstHome,
                        first.root().resolve("add-local-component.yml"),
                        mutableOutput));
        String cacheSourceAfter = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(secondHome).isNotEqualTo(firstHome);
        assertThat(mutableOutput.toAbsolutePath().normalize().startsWith(cacheSourceHome)).isFalse();
        assertThat(mutableOutput.toAbsolutePath().normalize().startsWith(first.root().resolve("out")
                .toAbsolutePath().normalize())).isTrue();
        assertThat(mutableOutput).exists();
        assertThat(cacheSourceAfter).isEqualTo(cacheSourceBefore);
        writeEvidence("add-java-mcp-server-and-test-caching-task-6-remediation-fresh.log",
                "PASS fresh fixture worker output isolation" + System.lineSeparator()
                        + "firstFreshHome=" + firstHome + System.lineSeparator()
                        + "secondFreshHome=" + secondHome + System.lineSeparator()
                        + "mutableOutput=" + mutableOutput.toAbsolutePath().normalize() + System.lineSeparator()
                        + "workerSuccess=" + result.response().success() + System.lineSeparator()
                        + "cacheSourceHome=" + cacheSourceHome + System.lineSeparator()
                        + "cacheSourceSha256Before=" + cacheSourceBefore + System.lineSeparator()
                        + "cacheSourceSha256After=" + cacheSourceAfter + System.lineSeparator());
    }

    @Test
    void cachedFixtureHomeMutationDoesNotReachSharedCacheOrNextFixture() throws Exception {
        DefaultLocalProfileQaFixtures first = DefaultLocalProfileQaFixtures.cached();
        Path firstHome = first.localHome();
        Path cacheSourceHome = DefaultLocalProfileHomeFixtures.cacheSourceHomeForTesting("local-home");
        String cacheSourceBefore = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);
        Path attemptedMutation = firstHome.resolve("bin").resolve("attempted-cache-mutation.txt");

        Files.write(attemptedMutation, "mutated copy only".getBytes(StandardCharsets.UTF_8));
        DefaultLocalProfileQaFixtures second = DefaultLocalProfileQaFixtures.cached();
        Path secondHome = second.localHome();
        String cacheSourceAfter = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);

        assertThat(attemptedMutation).exists();
        assertThat(secondHome.resolve("bin").resolve(attemptedMutation.getFileName())).doesNotExist();
        assertThat(cacheSourceAfter).isEqualTo(cacheSourceBefore);
        writeEvidence("add-java-mcp-server-and-test-caching-task-6-remediation-immutability.log",
                "PASS cached home mutation is isolated from shared cache" + System.lineSeparator()
                        + "firstHome=" + firstHome + System.lineSeparator()
                        + "secondHome=" + secondHome + System.lineSeparator()
                        + "attemptedMutation=" + attemptedMutation + System.lineSeparator()
                        + "cacheSourceHome=" + cacheSourceHome + System.lineSeparator()
                        + "cacheSourceSha256Before=" + cacheSourceBefore + System.lineSeparator()
                        + "cacheSourceSha256After=" + cacheSourceAfter + System.lineSeparator());
    }

    @Test
    void corruptedCachedSourceHomeIsRebuiltBeforeReuse() throws Exception {
        DefaultLocalProfileQaFixtures cached = DefaultLocalProfileQaFixtures.cached();
        cached.localHome();
        Path cacheSourceHome = DefaultLocalProfileHomeFixtures.cacheSourceHomeForTesting("local-home");
        String expectedDigest = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);
        Path corruptFile = cacheSourceHome.resolve("bin").resolve("user.properties");

        Files.write(corruptFile, "corrupt-cache-state".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        String corruptDigest = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);
        DefaultLocalProfileQaFixtures rebuilt = DefaultLocalProfileQaFixtures.cached();
        Path rebuiltHome = rebuilt.localHome();
        String rebuiltSourceDigest = DefaultLocalProfileHomeFixtures.homeDigestForTesting(cacheSourceHome);

        assertThat(corruptDigest).isNotEqualTo(expectedDigest);
        assertThat(rebuiltSourceDigest).isNotEqualTo(corruptDigest);
        assertThat(new String(Files.readAllBytes(rebuiltHome.resolve("bin").resolve("user.properties")),
                StandardCharsets.UTF_8)).doesNotContain("corrupt-cache-state");
        writeEvidence("add-java-mcp-server-and-test-caching-task-6-remediation-stale-cache.log",
                "PASS corrupt cached source was detected and rebuilt" + System.lineSeparator()
                        + "cacheSourceHome=" + cacheSourceHome + System.lineSeparator()
                        + "corruptFile=" + corruptFile + System.lineSeparator()
                        + "rebuiltHome=" + rebuiltHome + System.lineSeparator()
                        + "expectedSha256=" + expectedDigest + System.lineSeparator()
                        + "corruptSha256=" + corruptDigest + System.lineSeparator()
                        + "rebuiltSourceSha256=" + rebuiltSourceDigest + System.lineSeparator());
    }

    private static void writeEvidence(String fileName, String contents) throws IOException {
        Path evidence = Paths.get(".omo", "evidence", fileName);
        Files.createDirectories(evidence.getParent());
        Files.write(evidence, contents.getBytes(StandardCharsets.UTF_8));
    }
}
