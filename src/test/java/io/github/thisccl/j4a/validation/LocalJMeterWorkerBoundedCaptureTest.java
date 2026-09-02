package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import javax.tools.JavaCompiler;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerBoundedCaptureTest {
    private static final String NOISY_SAMPLER_CLASS = "io.github.thisccl.j4a.synthetic.NoisySampler";

    @Test
    void workerResponseBoundsCapturedOperationStdoutAndStderr() throws Exception {
        String previousReuse = System.getProperty("j4a.worker.reuse");
        System.setProperty("j4a.worker.reuse", "false");
        try {
            Path home = noisyJMeterHome();
            LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                    Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

            LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.componentDetails(
                    home, NOISY_SAMPLER_CLASS + "Gui"));

            assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
            assertThat(result.response().payload()).contains("component: " + NOISY_SAMPLER_CLASS + "Gui");
            assertThat(result.response().stdout()).contains("stdout truncated", "NOISY_STDOUT_TAIL");
            assertThat(result.response().stdout()).doesNotContain("NOISY_STDOUT_HEAD");
            assertThat(result.response().stderr()).contains("stderr truncated", "NOISY_STDERR_TAIL");
            assertThat(result.response().stderr()).doesNotContain("NOISY_STDERR_HEAD");
            assertThat(result.response().stdout().length()).isLessThan(20 * 1024);
            assertThat(result.response().stderr().length()).isLessThan(20 * 1024);
            assertThat(LocalJMeterWorkerResponse.fromJsonLine(result.response().toJsonLine()).success()).isTrue();
            assertThat(result.response().toJsonLine().length()).isLessThan(64 * 1024);
            assertThat(result.workerExited()).isTrue();
        } finally {
            if (previousReuse == null) {
                System.clearProperty("j4a.worker.reuse");
            } else {
                System.setProperty("j4a.worker.reuse", previousReuse);
            }
        }
    }

    private static Path noisyJMeterHome() throws IOException {
        Path home = Files.createTempDirectory("j4a-noisy-worker-home-");
        DefaultLocalProfileHomeFixtures.createHome(home, false);
        createNoisySampler(home.resolve("lib").resolve("ext").resolve("synthetic-noisy-sampler.jar"));
        return home.toAbsolutePath().normalize();
    }

    private static void createNoisySampler(Path jar) throws IOException {
        JavaCompiler compiler = SyntheticPluginJarCompiler.compiler();
        Path work = jar.getParent().resolve("plugin-src-NoisySampler");
        Path source = work.resolve(NOISY_SAMPLER_CLASS.replace('.', '/') + ".java");
        Path guiSource = work.resolve((NOISY_SAMPLER_CLASS + "Gui").replace('.', '/') + ".java");
        SyntheticPluginJarCompiler.writeSource(source, noisySamplerSource());
        SyntheticPluginJarCompiler.writeSource(guiSource, noisyGuiSource());
        SyntheticPluginJarCompiler.compile(compiler, Arrays.asList(source.toFile(), guiSource.toFile()),
                work.resolve("classes"));
        SyntheticPluginJarCompiler.writeJar(jar, work.resolve("classes"),
                NOISY_SAMPLER_CLASS, NOISY_SAMPLER_CLASS + "Gui");
    }

    private static String noisySamplerSource() {
        return "package io.github.thisccl.j4a.synthetic;\n"
                + "public class NoisySampler extends org.apache.jmeter.samplers.AbstractSampler {\n"
                + "  static {\n"
                + "    System.out.println(\"NOISY_STDOUT_HEAD\");\n"
                + "    for (int index = 0; index < 6000; index++) {\n"
                + "      System.out.println(\"NOISY_STDOUT_CHUNK_\" + index + \"_ABCDEFGHIJKLMNOPQRSTUVWXYZ\");\n"
                + "    }\n"
                + "    System.out.println(\"NOISY_STDOUT_TAIL\");\n"
                + "    System.err.println(\"NOISY_STDERR_HEAD\");\n"
                + "    for (int index = 0; index < 6000; index++) {\n"
                + "      System.err.println(\"NOISY_STDERR_CHUNK_\" + index + \"_ABCDEFGHIJKLMNOPQRSTUVWXYZ\");\n"
                + "    }\n"
                + "    System.err.println(\"NOISY_STDERR_TAIL\");\n"
                + "  }\n"
                + "  public org.apache.jmeter.samplers.SampleResult sample(org.apache.jmeter.samplers.Entry entry) {\n"
                + "    org.apache.jmeter.samplers.SampleResult result = new org.apache.jmeter.samplers.SampleResult();\n"
                + "    result.sampleStart();\n"
                + "    result.setSuccessful(true);\n"
                + "    result.setResponseCodeOK();\n"
                + "    result.sampleEnd();\n"
                + "    return result;\n"
                + "  }\n"
                + "}\n";
    }

    private static String noisyGuiSource() {
        String source = SyntheticPluginSourceTemplates.guiSource(
                NOISY_SAMPLER_CLASS,
                NOISY_SAMPLER_CLASS + "Gui",
                "org.apache.jmeter.samplers.gui.AbstractSamplerGui");
        String declaration = "public class NoisySamplerGui extends org.apache.jmeter.samplers.gui.AbstractSamplerGui {\n";
        String constructor = "  public NoisySamplerGui() {\n"
                + "    System.out.println(\"NOISY_STDOUT_HEAD\");\n"
                + "    for (int index = 0; index < 6000; index++) {\n"
                + "      System.out.println(\"NOISY_STDOUT_CHUNK_\" + index + \"_ABCDEFGHIJKLMNOPQRSTUVWXYZ\");\n"
                + "    }\n"
                + "    System.out.println(\"NOISY_STDOUT_TAIL\");\n"
                + "    System.err.println(\"NOISY_STDERR_HEAD\");\n"
                + "    for (int index = 0; index < 6000; index++) {\n"
                + "      System.err.println(\"NOISY_STDERR_CHUNK_\" + index + \"_ABCDEFGHIJKLMNOPQRSTUVWXYZ\");\n"
                + "    }\n"
                + "    System.err.println(\"NOISY_STDERR_TAIL\");\n"
                + "  }\n";
        return source.replace(declaration, declaration + constructor);
    }
}
