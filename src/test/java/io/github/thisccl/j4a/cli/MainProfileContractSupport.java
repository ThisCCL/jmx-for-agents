package io.github.thisccl.j4a.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class MainProfileContractSupport {
    static final String HTTP_SAMPLER_LOCATOR = "jmx_330976848c8e";

    private MainProfileContractSupport() {
    }

    static Path validLookingLocalHome(Path tempDir) throws IOException {
        Path home = tempDir.resolve("local-home-" + System.nanoTime());
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("saveservice.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("upgrade.properties"), new byte[0]);
        return home;
    }

    static String localSetPatch() {
        return ""
                + "changes:\n"
                + "  - set:\n"
                + "      ref: " + HTTP_SAMPLER_LOCATOR + "\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: HTTPSampler\\.domain\n"
                + "          value: changed.example\n"
                + "          type: string\n";
    }

    static String localAddPatch() {
        return ""
                + "changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n";
    }
}
