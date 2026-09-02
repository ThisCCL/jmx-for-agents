package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class J4aMcpRuntimeCacheContractTest {
    @Test
    void runtimeBuildsRegistryAndFingerprintsExactlyOncePerWorker() throws Exception {
        Path home = syntheticHome();
        Path registryBuilds = home.resolve("registry-builds.txt");
        Path fingerprintScans = home.resolve("fingerprint-scans.txt");
        Files.write(home.resolve("bin/user.properties"), java.util.Arrays.asList(
                "j4a.fake.registry.build.count=" + registryBuilds,
                "j4a.fake.fingerprint.scan.count=" + fingerprintScans));
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult first = pool.execute(LocalJMeterWorkerRequest.discoverComponents(home));
            LocalJMeterWorkerResult second = pool.execute(LocalJMeterWorkerRequest.discoverComponents(home));

            assertThat(first.response().success()).isTrue();
            assertThat(second.response().success()).isTrue();
            assertThat(Files.readAllLines(registryBuilds, StandardCharsets.UTF_8)).hasSize(1);
            assertThat(Files.readAllLines(fingerprintScans, StandardCharsets.UTF_8)).hasSize(1);
        } finally {
            pool.close();
        }
    }

    @Test
    void successfulDescriptorBuildIsCachedPerComponent() throws Exception {
        Path home = syntheticHome();
        Path descriptorBuilds = home.resolve("descriptor-builds.txt");
        Files.write(home.resolve("bin/user.properties"), java.util.Collections.singletonList(
                "j4a.fake.descriptor.build.count=" + descriptorBuilds));
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult first = pool.execute(
                    LocalJMeterWorkerRequest.componentDetails(home, "cached-descriptor"));
            LocalJMeterWorkerResult second = pool.execute(
                    LocalJMeterWorkerRequest.componentDetails(home, "cached-descriptor"));

            assertThat(first.response().success()).isTrue();
            assertThat(second.response().success()).isTrue();
            assertThat(Files.readAllLines(descriptorBuilds, StandardCharsets.UTF_8)).hasSize(1);
        } finally {
            pool.close();
        }
    }

    @Test
    void failedDescriptorBuildIsRemovedAndRetried() throws Exception {
        Path home = syntheticHome();
        Path descriptorBuilds = home.resolve("descriptor-builds.txt");
        Files.write(home.resolve("bin/user.properties"), java.util.Collections.singletonList(
                "j4a.fake.descriptor.build.count=" + descriptorBuilds));
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult first = pool.execute(
                    LocalJMeterWorkerRequest.componentDetails(home, "descriptor-retry"));
            LocalJMeterWorkerResult second = pool.execute(
                    LocalJMeterWorkerRequest.componentDetails(home, "descriptor-retry"));

            assertThat(first.response().success()).isFalse();
            assertThat(second.response().success()).isTrue();
            assertThat(Files.readAllLines(descriptorBuilds, StandardCharsets.UTF_8)).hasSize(2);
        } finally {
            pool.close();
        }
    }

    private static Path syntheticHome() throws Exception {
        Class<?> testSupport = Class.forName(
                "io.github.thisccl.j4a.validation.LocalJMeterSharedWorkersTest");
        java.lang.reflect.Method fakeHome = testSupport.getDeclaredMethod("fakeJMeterHome");
        fakeHome.setAccessible(true);
        return (Path) fakeHome.invoke(null);
    }
}
